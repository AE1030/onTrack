package org.tracker.gpatracker.leaderboard.model;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LeaderboardAvatarConverterTest {

    private final LeaderboardAvatarConverter converter = new LeaderboardAvatarConverter();

    @Test
    @DisplayName("round-trips through the column")
    void roundTrip() {
        LeaderboardAvatar avatar = new LeaderboardAvatar(5, 7, 0, 3, 2);
        String column = converter.convertToDatabaseColumn(avatar);

        assertThat(column).isEqualTo("5,7,0,3,2");
        assertThat(converter.convertToEntityAttribute(column)).isEqualTo(avatar);
    }

    @Test
    @DisplayName("null and unreadable values load as null so the handle's face is drawn instead")
    void unreadableIsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("")).isNull();
        assertThat(converter.convertToEntityAttribute("1,2,3")).isNull();
        assertThat(converter.convertToEntityAttribute("a,b,c,d,e")).isNull();
    }

    @Test
    @DisplayName("an axis outside what the client can draw fails validation")
    void outOfRangeIsInvalid() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThat(validator.validate(new LeaderboardAvatar(5, 7, 7, 3, 5))).isEmpty();
        assertThat(validator.validate(new LeaderboardAvatar(6, 0, 0, 0, 0))).hasSize(1);
        assertThat(validator.validate(new LeaderboardAvatar(0, 0, 0, -1, 0))).hasSize(1);
    }
}
