package org.tracker.gpatracker.leaderboard.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a handle is allowed to be.
 *
 * <p>Uniqueness is not tested here — it is a database property, and lives in
 * {@code LeaderboardTenancyIntegrationTest} where a real index can enforce it.
 */
class HandlePolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"ahmed", "a_b-c", "Student2026", "abc", "aaaaaaaaaaaaaaaaaaaaaaaa"})
    @DisplayName("ordinary handles are accepted")
    void ordinaryHandlesPass(String handle) {
        assertThat(HandlePolicy.rejectionReason(handle)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "aaaaaaaaaaaaaaaaaaaaaaaaa"})
    @DisplayName("length bounds hold at both ends")
    void lengthBoundsHold(String handle) {
        assertThat(HandlePolicy.rejectionReason(handle)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"two words", "semi;colon", "emoji😀", "quote'd"})
    @DisplayName("a handle is one token, not free text")
    void characterSetIsRestricted(String handle) {
        assertThat(HandlePolicy.rejectionReason(handle)).isNotNull();
    }

    @Test
    @DisplayName("a missing handle is rejected rather than defaulted")
    void missingHandleIsRejected() {
        assertThat(HandlePolicy.rejectionReason(null)).isNotNull();
        assertThat(HandlePolicy.rejectionReason("   ")).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fuckyou", "sh1thead", "b1tch", "n0tanazi", "admin", "onTrackOfficial"})
    @DisplayName("the screen sees through the usual substitutions")
    void profanityScreenNormalisesFirst(String handle) {
        assertThat(HandlePolicy.isScreened(handle)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ahmed", "classic", "assignment1", "passfail", "grader"})
    @DisplayName("the screen does not fire on ordinary words")
    void screenDoesNotOverreach(String handle) {
        assertThat(HandlePolicy.isScreened(handle)).isFalse();
    }

    /**
     * The reason the word lists are split in two. Every handle here contains a blocked term as a
     * substring and is entirely innocent, so a single loose list would reject all of them.
     */
    @ParameterizedTest
    @ValueSource(strings = {"password1", "among_us", "basement", "TheAnalyst", "cocktail",
                            "spiceGirl", "therapist", "tycoon", "teaspoon", "button_masher",
                            "competition", "pakistan", "essex_lad"})
    @DisplayName("words that merely contain a blocked term are allowed")
    void innocentWordsContainingBlockedTermsPass(String handle) {
        assertThat(HandlePolicy.isScreened(handle)).isFalse();
    }

    /**
     * The same terms, standing alone or as their own token. A word list that cannot catch these
     * is not doing anything, so both halves of the split are asserted together.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ass", "xX_ass_Xx", "bigAss", "tit4tat", "Mong", "sk8er_twat"})
    @DisplayName("a blocked term as its own token is still caught")
    void blockedTermsAsTokensAreCaught(String handle) {
        assertThat(HandlePolicy.isScreened(handle)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fuuuuck", "f_u_c_k", "shiiiit", "bitchhh"})
    @DisplayName("padding a word out does not hide it")
    void repeatedLettersDoNotHide(String handle) {
        assertThat(HandlePolicy.isScreened(handle)).isTrue();
    }

    /**
     * Documenting a known limit rather than pretending it is absent: terms on the substring list
     * still match inside a longer word. They were chosen because no common English word contains
     * them, but the dictionary is larger than the list that was checked against.
     */
    @Test
    @DisplayName("the substring list over-blocks, knowingly")
    void substringScreenOverBlocks() {
        assertThat(HandlePolicy.isScreened("scunthorpe")).isTrue();
    }
}
