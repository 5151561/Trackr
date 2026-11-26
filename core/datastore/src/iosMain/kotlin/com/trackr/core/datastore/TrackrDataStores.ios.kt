package com.trackr.core.datastore

import okio.FileSystem

actual fun systemFileSystem(): FileSystem = FileSystem.SYSTEM
