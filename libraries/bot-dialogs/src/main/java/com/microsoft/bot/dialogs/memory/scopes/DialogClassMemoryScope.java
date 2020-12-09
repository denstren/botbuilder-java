package com.microsoft.bot.dialogs.memory.scopes;

import com.microsoft.bot.dialogs.DialogContainer;
import com.microsoft.bot.dialogs.DialogContext;
import com.microsoft.bot.dialogs.ScopePath;

/**
 * Initializes a new instance of the DialogClassMemoryScope class.
 */
public class DialogClassMemoryScope extends MemoryScope {
    /**
     * Initializes a new instance of the DialogClassMemoryScope class.
     */
    public DialogClassMemoryScope() {
        super(ScopePath.DIALOG_CLASS, true);
    }

    /**
     * Get the backing memory for this scope.
     */
    @Override
    public final Object getMemory(DialogContext dialogContext) {
        if (dialogContext == null) {
            throw new IllegalArgumentException("dialogContext cannot be null.");
        }
        // if active dialog is a container dialog then "dialogclass" binds to it.
        if (dialogContext.getActiveDialog() != null) {
            Object dialog = dialogContext.findDialog(dialogContext.getActiveDialog().getId());
            if (dialog instanceof DialogContainer) {
                return new ReadOnlyObject(dialog);
            }
        }

        // Otherwise we always bind to parent, or if there is no parent the active
        // dialog
        if (dialogContext.getParent() != null) {
            return new ReadOnlyObject(dialogContext.findDialog(dialogContext.getParent().getActiveDialog().getId()));
        } else {
            return new ReadOnlyObject(dialogContext.findDialog(dialogContext.getActiveDialog().getId()));
        }
    }

    /**
     * Changes the backing Object for the memory scope.
     */
    @Override
    public final void setMemory(DialogContext dialogContext, Object memory) {
        throw new UnsupportedOperationException("You can't modify the dialogclass scope");
    }
}
