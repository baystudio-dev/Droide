package com.baystudio.droide.core

/** A retained workspace process owner needs recovery, not a provider/model retry. */
internal class WorkspaceProcessRecoveryRequiredException(message: String, val earlierRuntime: Boolean = false) : IllegalStateException(message)
