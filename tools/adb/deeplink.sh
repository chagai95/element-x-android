#! /bin/bash

# Copyright (c) 2025 Element Creations Ltd.
# Copyright 2023-2024 New Vector Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
# Please see LICENSE files in the repository root for full details.

# Format is:
# matrixchat://open/{sessionId} to open a session
# matrixchat://open/{sessionId}/{roomId} to open a room
# matrixchat://open/{sessionId}/{roomId}/{eventId} to open a thread

# Open a session
# adb shell am start -a android.intent.action.VIEW -d matrixchat://open/@benoit10518:matrix.org
# Open a room
adb shell am start -a android.intent.action.VIEW -d matrixchat://open/@benoit10518:matrix.org/!dehdDVSkabQLZFYrgo:matrix.org
# Open a thread
# adb shell am start -a android.intent.action.VIEW -d matrixchat://open/@benoit10518:matrix.org/!dehdDVSkabQLZFYrgo:matrix.org/\\\$threadId
