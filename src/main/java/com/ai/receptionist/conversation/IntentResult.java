package com.ai.receptionist.conversation;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.ai.receptionist.utils.ConversationIntent;

/**
 * Result of multi-intent classification. Supports conflict detection for strict confirmation gating.
 */
public final class IntentResult {

    private final Set<ConversationIntent> intents;
    private final boolean hasConflict;

    public IntentResult(Set<ConversationIntent> intents, boolean hasConflict) {
        this.intents = intents != null ? Collections.unmodifiableSet(new HashSet<>(intents)) : Collections.emptySet();
        this.hasConflict = hasConflict;
    }

    public Set<ConversationIntent> getIntents() {
        return intents;
    }

    public boolean hasConflict() {
        return hasConflict;
    }

    public boolean hasIntent(ConversationIntent intent) {
        return intents.contains(intent);
    }

    public boolean isSingleIntent(ConversationIntent intent) {
        return intents.size() == 1 && intents.contains(intent);
    }

    public static IntentResult single(ConversationIntent intent) {
        return new IntentResult(Set.of(intent), false);
    }

    public static IntentResult empty() {
        return new IntentResult(Collections.emptySet(), false);
    }
}
