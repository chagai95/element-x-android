#!/usr/bin/env python3
# Copyright (c) 2026 Chagai Friedlander
# SPDX-License-Identifier: AGPL-3.0-only
"""
Generate the Matrix Chat alarm tones.

Every tone here is **synthesised from scratch** with numpy — no sampled instrument, no
ringtone from another phone, nothing anybody else owns. That matters twice over: the app
is AGPL and shipping a copyrighted ringtone inside it would be a licence problem, and a
tone that is generated is a tone we can tune (length, brightness, how hard it pushes)
instead of one we have to accept.

Design rules, all of which exist because this is an *alarm*:

* **Seamless loops.** The alarm plays with ``MediaPlayer.setLooping(true)``, so a click or
  a gap at the seam would be audible every few seconds and would quickly sound broken.
  Each tone therefore ends in silence (or is cross-faded into its own head) and is an
  exact whole number of beats long.
* **Loud, but not clipped.** Peak-normalised to -1 dBFS. The alarm's own loudness comes
  from taking over ``STREAM_ALARM`` and from a software ramp; the file itself just has to
  be as hot as it can be without distorting.
* **Attention through movement, not through harshness.** A steady tone is easy to sleep
  through and unpleasant to wake to. These all move — rising arpeggios, cascades, a
  swell — because a changing sound is what the ear refuses to habituate to.
* **Mono, 44.1 kHz, Ogg Vorbis q6.** Mono halves the size for no audible loss on a phone
  speaker; Vorbis is decoded natively by Android and needs no library.

Usage:
    python3 tools/chagai/make_tones.py \
        --out features/callreminders/impl/src/main/res/raw

The ids written here are the ones ``AlarmTone.kt`` refers to; adding a tone means adding
it in both places.
"""

from __future__ import annotations

import argparse
import math
import os
import shutil
import subprocess
import sys
import tempfile
import wave

import numpy as np

SR = 44_100
PEAK_DBFS = -1.0


# --------------------------------------------------------------------------------------
# building blocks
# --------------------------------------------------------------------------------------
def t(n: int) -> np.ndarray:
    """A time axis of n samples, in seconds."""
    return np.arange(n, dtype=np.float64) / SR


def silence(seconds: float) -> np.ndarray:
    return np.zeros(int(round(seconds * SR)), dtype=np.float64)


def add_at(buf: np.ndarray, start_s: float, sound: np.ndarray) -> None:
    """Mix `sound` into `buf` at `start_s`, clipped to the buffer (in place)."""
    i = int(round(start_s * SR))
    if i >= len(buf):
        return
    n = min(len(sound), len(buf) - i)
    buf[i:i + n] += sound[:n]


def adsr(n: int, attack: float, decay: float, sustain: float, release: float, sustain_level: float = 0.6) -> np.ndarray:
    """A plain ADSR envelope, lengths in seconds, padded/truncated to n samples."""
    a, d, s, r = (int(round(x * SR)) for x in (attack, decay, sustain, release))
    env = np.concatenate([
        np.linspace(0.0, 1.0, max(a, 1)),
        np.linspace(1.0, sustain_level, max(d, 1)),
        np.full(max(s, 1), sustain_level),
        np.linspace(sustain_level, 0.0, max(r, 1)) ** 1.5,
    ])
    if len(env) < n:
        env = np.concatenate([env, np.zeros(n - len(env))])
    return env[:n]


def percussive(n: int, decay_s: float, curve: float = 1.0) -> np.ndarray:
    """
    A struck-instrument envelope: instant attack, exponential decay.

    `curve` > 1 makes the tail die faster than a true exponential, which is what makes a
    marimba read as wooden rather than as a sustained organ.
    """
    env = np.exp(-t(n) / max(decay_s, 1e-6)) ** curve
    # A 2 ms fade-in removes the click an instant attack would otherwise put at the onset.
    ramp = min(int(0.002 * SR), n)
    env[:ramp] *= np.linspace(0.0, 1.0, ramp)
    return env


def marimba(freq: float, dur: float, decay: float = 0.55, gain: float = 1.0) -> np.ndarray:
    """
    A marimba-ish struck bar: a strong fundamental, a quiet 4th harmonic (the partial that
    gives a real marimba its hollow wooden ring) and a very short noisy "mallet" click.
    """
    n = int(round(dur * SR))
    time = t(n)
    body = (
        1.00 * np.sin(2 * np.pi * freq * time) * percussive(n, decay, 1.0) +
        0.28 * np.sin(2 * np.pi * freq * 4.0 * time) * percussive(n, decay * 0.35, 1.4) +
        0.10 * np.sin(2 * np.pi * freq * 9.2 * time) * percussive(n, decay * 0.15, 2.0)
    )
    rng = np.random.default_rng(int(freq))
    click = rng.normal(0.0, 1.0, n) * percussive(n, 0.004, 1.0) * 0.12
    return gain * (body + click)


def bell(freq: float, dur: float, decay: float = 1.6, gain: float = 1.0) -> np.ndarray:
    """
    A tubular-bell strike. The inharmonic partials (2.76, 5.40, 8.93 × f) are the classic
    bell ratios — they are what makes a bell sound like a bell and not like a sine.
    """
    n = int(round(dur * SR))
    time = t(n)
    partials = ((1.0, 1.0, 1.0), (2.76, 0.52, 0.62), (5.40, 0.30, 0.38), (8.93, 0.16, 0.22))
    out = np.zeros(n)
    for ratio, amp, dec in partials:
        out += amp * np.sin(2 * np.pi * freq * ratio * time) * percussive(n, decay * dec, 1.0)
    return gain * out


def fm_voice(freq: float, dur: float, index: float = 2.2, decay: float = 0.5, gain: float = 1.0) -> np.ndarray:
    """One FM operator pair — a bright, slightly metallic plucked voice."""
    n = int(round(dur * SR))
    time = t(n)
    mod = np.sin(2 * np.pi * freq * 2.0 * time) * index * percussive(n, decay * 0.4)
    return gain * np.sin(2 * np.pi * freq * time + mod) * percussive(n, decay, 1.1)


def lowpass(x: np.ndarray, cutoff: float) -> np.ndarray:
    """A one-pole lowpass. Enough to take the fizz off a saw without pulling in scipy."""
    a = math.exp(-2 * math.pi * cutoff / SR)
    out = np.empty_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc = (1 - a) * v + a * acc
        out[i] = acc
    return out


def saw(freq: np.ndarray | float, n: int) -> np.ndarray:
    """A band-limited-enough sawtooth, from a phase accumulator so freq may glide."""
    f = np.full(n, float(freq)) if np.isscalar(freq) else np.asarray(freq, dtype=np.float64)
    phase = np.cumsum(f) / SR
    return 2.0 * (phase - np.floor(phase + 0.5))


def loop_seam(x: np.ndarray, fade_s: float = 0.01) -> np.ndarray:
    """
    Make the very end meet the very start without a click.

    Short fade at both edges: the loop point then has matched zero-crossings, which is all
    MediaPlayer's looping needs to be inaudible.
    """
    n = min(int(fade_s * SR), len(x) // 4)
    if n <= 0:
        return x
    out = x.copy()
    out[:n] *= np.linspace(0.0, 1.0, n)
    out[-n:] *= np.linspace(1.0, 0.0, n)
    return out


def normalise(x: np.ndarray, dbfs: float = PEAK_DBFS) -> np.ndarray:
    """Peak-normalise. Alarms want every dB they can get, short of clipping."""
    peak = float(np.max(np.abs(x))) or 1.0
    return x * (10 ** (dbfs / 20.0) / peak)


def soft_limit(x: np.ndarray, drive: float = 2.6) -> np.ndarray:
    """
    Push the quiet parts up without letting the peaks clip.

    Peak-normalising alone is not enough for an alarm: a struck marimba spends most of its
    loop decaying, so its *average* level — which is what loudness actually sounds like —
    sits ~14 dB below the peak, and the tone reads as thin on a phone speaker however high
    the volume is. A tanh soft clipper fixes that in one step: it is linear through the
    middle and bends smoothly near ±1, so transient peaks are rounded off rather than
    squared off and the average comes up ~4-5 dB with no audible distortion. This is the
    same thing a mastering limiter does, in three lines and with no dependency.
    """
    normalised = normalise(x, 0.0)
    return np.tanh(drive * normalised) / math.tanh(drive)


# --------------------------------------------------------------------------------------
# the tones
# --------------------------------------------------------------------------------------
# A pentatonic scale: no semitone clashes, so an arpeggio cannot land on a sour interval
# however it is ordered. Frequencies in Hz, roughly C5 upward.
C_MAJ_PENT = [523.25, 587.33, 659.25, 783.99, 880.00, 1046.50, 1174.66, 1318.51]


def tone_marimba_rise() -> np.ndarray:
    """
    "Marimba Rise" — the default.

    A pentatonic arpeggio that climbs, repeated four times with each pass a little louder
    and a little higher, over a soft low octave on the beat. Bright and fast enough to
    pull your eyes to the phone, warm enough that hearing it at 07:00 is not a punishment.
    6.4 s, 16 notes.
    """
    beat = 0.2
    buf = silence(beat * 32)
    pattern = [0, 2, 4, 5, 4, 2, 5, 7]
    for pass_idx in range(4):
        gain = 0.55 + 0.15 * pass_idx
        for k, step in enumerate(pattern):
            idx = min(step + pass_idx, len(C_MAJ_PENT) - 1)
            add_at(buf, (pass_idx * 8 + k) * beat, marimba(C_MAJ_PENT[idx], 0.7, decay=0.33, gain=gain))
        # A low octave under the first beat of each pass gives the loop a pulse.
        add_at(buf, pass_idx * 8 * beat, marimba(C_MAJ_PENT[0] / 2, 1.0, decay=0.5, gain=0.32))
    return loop_seam(buf, 0.02)


def tone_sunrise() -> np.ndarray:
    """
    "Sunrise" — gentle at the top of the loop, insistent by the end.

    A quiet swelling pad with one note on it, then two, then four, then eight: the tone
    itself escalates, so even at a fixed volume it feels like it is getting more urgent.
    For waking up rather than for being interrupted. 8 s.
    """
    total = 8.0
    buf = silence(total)
    n = len(buf)
    time = t(n)
    # The pad: a fifth, swelling in and back out so the loop breathes.
    swell = np.sin(np.pi * time / total) ** 1.6
    pad = (
        0.5 * np.sin(2 * np.pi * 261.63 * time) +
        0.35 * np.sin(2 * np.pi * 392.00 * time) +
        0.18 * np.sin(2 * np.pi * 523.25 * time * (1 + 0.0008 * np.sin(2 * np.pi * 0.7 * time)))
    )
    buf += 0.30 * pad * swell
    # The notes get denser: 1 note in the first 2 s, 2 in the next, 4, then 8.
    schedule = [(0.0, 1), (2.0, 2), (4.0, 4), (6.0, 8)]
    for start, count in schedule:
        span = 2.0
        for k in range(count):
            at = start + span * k / count
            freq = C_MAJ_PENT[(k * 2) % len(C_MAJ_PENT)]
            gain = 0.30 + 0.55 * (start / total)
            add_at(buf, at, fm_voice(freq, 1.2, index=1.4 + start / 4, decay=0.45, gain=gain))
    return loop_seam(buf, 0.03)


def tone_retro_ring() -> np.ndarray:
    """
    "Old Telephone" — a two-tone bell burst, ring-ring … pause.

    Built from two close sine partials beating against each other and chopped at 22 Hz,
    which is how a mechanical twin-bell handset actually sounds: the hammer striking
    between two bells. Deliberately *not* any phone network's standard cadence. 4 s.
    """
    buf = silence(4.0)
    for start in (0.0, 1.15):
        dur = 0.9
        n = int(dur * SR)
        time = t(n)
        tone = (
            np.sin(2 * np.pi * 1_040 * time) +
            0.85 * np.sin(2 * np.pi * 1_295 * time) +
            0.25 * np.sin(2 * np.pi * 2_600 * time)
        )
        # The hammer: a hard 22 Hz chop, which is the "trill" of a real bell.
        chop = (np.sin(2 * np.pi * 22 * time) > 0).astype(np.float64)
        chop = lowpass(chop, 900)
        env = adsr(n, 0.01, 0.05, dur - 0.16, 0.10, sustain_level=0.85)
        add_at(buf, start, 0.6 * tone * chop * env)
    return loop_seam(buf, 0.01)


def tone_bell_cascade() -> np.ndarray:
    """
    "Bell Cascade" — bells falling down a scale and catching each other.

    Long inharmonic tails that overlap into a shimmer. Pleasant, carries a long way, and
    distinctive enough that you know which app is ringing without looking. 6 s.
    """
    buf = silence(6.0)
    steps = [7, 5, 4, 2, 5, 4, 2, 0, 4, 2, 0]
    for k, step in enumerate(steps):
        freq = C_MAJ_PENT[step]
        add_at(buf, k * 0.42, bell(freq, 3.2, decay=1.5, gain=0.5 - 0.02 * k))
        if k % 4 == 0:
            add_at(buf, k * 0.42, bell(freq / 2, 4.0, decay=2.0, gain=0.22))
    return loop_seam(buf, 0.04)


def tone_synth_pulse() -> np.ndarray:
    """
    "Pulse" — the one for when it absolutely must not be slept through.

    A filtered saw stab on every eighth, a siren-ish pitch sweep every bar, and a tremolo
    on top. Modern, urgent, mildly alarming by design. 4.8 s.
    """
    beat = 0.15
    buf = silence(beat * 32)
    for k in range(32):
        if k % 4 == 3:
            continue  # the gap is what makes the rest feel like a pulse rather than a drone
        n = int(0.16 * SR)
        base = 220.0 * (2 ** ((k % 8) / 12.0))
        raw = saw(base, n) + 0.5 * saw(base * 1.005, n)
        voice = lowpass(raw, 1_400 + 260 * (k % 8)) * percussive(n, 0.12, 1.2)
        add_at(buf, k * beat, 0.45 * voice)
    # The sweep: one rising glide per loop, the thing the ear tracks.
    n = int(1.2 * SR)
    glide = np.linspace(520.0, 1_240.0, n)
    sweep = lowpass(saw(glide, n), 2_600) * adsr(n, 0.25, 0.2, 0.4, 0.35, 0.7)
    add_at(buf, 3.2, 0.22 * sweep)
    time = t(len(buf))
    buf *= 0.78 + 0.22 * np.sin(2 * np.pi * 5.5 * time)
    return loop_seam(buf, 0.01)


# id (also the res/raw file name)  ->  (builder, drive for soft_limit, human label)
# The drive is per tone: "Sunrise" is meant to start quiet, so it is barely limited, while
# "Pulse" is meant to be the loudest thing in the room and is pushed hard.
TONES = {
    "alarm_marimba_rise": (tone_marimba_rise, 2.8, "Marimba Rise — the default"),
    "alarm_sunrise": (tone_sunrise, 1.6, "Sunrise — gentle, then insistent"),
    "alarm_retro_ring": (tone_retro_ring, 3.2, "Old Telephone"),
    "alarm_bell_cascade": (tone_bell_cascade, 2.6, "Bell Cascade"),
    "alarm_synth_pulse": (tone_synth_pulse, 3.4, "Pulse — hardest to ignore"),
}


# --------------------------------------------------------------------------------------
# writing
# --------------------------------------------------------------------------------------
def write_ogg(samples: np.ndarray, path: str, drive: float) -> None:
    """numpy -> 16-bit WAV -> Ogg Vorbis via ffmpeg (the only encoder on this box)."""
    pcm = np.clip(normalise(soft_limit(samples, drive)), -1.0, 1.0)
    ints = (pcm * 32_767.0).astype("<i2")
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, "tone.wav")
        with wave.open(wav, "wb") as handle:
            handle.setnchannels(1)
            handle.setsampwidth(2)
            handle.setframerate(SR)
            handle.writeframes(ints.tobytes())
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-qscale:a", "6", path],
            check=True,
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--out",
        default=os.path.join(
            os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
            "features/callreminders/impl/src/main/res/raw",
        ),
        help="res/raw directory to write the .ogg files into",
    )
    parser.add_argument("--only", help="generate just this tone id")
    args = parser.parse_args()

    if shutil.which("ffmpeg") is None:
        print("ffmpeg is required to encode Ogg Vorbis", file=sys.stderr)
        return 1

    os.makedirs(args.out, exist_ok=True)
    for tone_id, (build, drive, label) in TONES.items():
        if args.only and args.only != tone_id:
            continue
        samples = build()
        path = os.path.join(args.out, f"{tone_id}.ogg")
        write_ogg(samples, path, drive)
        print(f"{tone_id:24s} {len(samples) / SR:5.2f}s  {os.path.getsize(path) / 1024:6.1f} KiB  {label}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
