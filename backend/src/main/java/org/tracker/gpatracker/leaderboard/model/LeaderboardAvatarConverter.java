package org.tracker.gpatracker.leaderboard.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores a {@link LeaderboardAvatar} as {@code "face,skin,hair,gear,accent"}.
 *
 * <p>An unreadable value loads as null rather than failing the row: a null avatar just means the
 * client draws the handle's face, which is a worse picture, not a broken board.
 */
@Converter
public class LeaderboardAvatarConverter implements AttributeConverter<LeaderboardAvatar, String> {

    @Override
    public String convertToDatabaseColumn(LeaderboardAvatar avatar) {
        if (avatar == null) {
            return null;
        }
        return avatar.face() + "," + avatar.skin() + "," + avatar.hair() + ","
                + avatar.gear() + "," + avatar.accent();
    }

    @Override
    public LeaderboardAvatar convertToEntityAttribute(String column) {
        if (column == null || column.isBlank()) {
            return null;
        }
        String[] parts = column.split(",");
        if (parts.length != 5) {
            return null;
        }
        try {
            return new LeaderboardAvatar(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()),
                    Integer.parseInt(parts[3].trim()),
                    Integer.parseInt(parts[4].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
