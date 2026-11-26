package com.baystudio.droide.ui

import androidx.compose.runtime.saveable.Saver
import com.baystudio.droide.core.WorkspaceSearchInput

/** Keep large pastes available in the field without putting them in an Activity state Bundle. */
internal val SearchTextStateSaver = Saver<String, String>(
    save = { text -> text.takeIf(WorkspaceSearchInput::admitted) },
    // A previous app version may already have saved a long field; retain it for the live session.
    restore = { it },
)
