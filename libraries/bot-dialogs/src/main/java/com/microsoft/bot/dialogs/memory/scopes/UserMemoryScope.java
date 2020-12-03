package com.microsoft.bot.dialogs.memory.scopes;

import com.microsoft.bot.builder.UserState;
import com.microsoft.bot.dialogs.memory.ScopePath;

/**
 * MemoryScope represents a named memory scope abstract class.
 */
public class UserMemoryScope extends BotStateMemoryScope<UserState> {
    /**
     * DialogMemoryScope maps "this" -> dc.ActiveDialog.State.
     */
    public UserMemoryScope() {
        super(UserState.class, ScopePath.USER);
    }
}