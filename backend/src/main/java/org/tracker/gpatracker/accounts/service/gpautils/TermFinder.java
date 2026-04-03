package org.tracker.gpatracker.accounts.service.gpautils;
import java.util.regex.Pattern;

public class TermFinder {
    private final Pattern termPattern = Pattern.compile("^---\\s\\d{4}\\s[A-Za-z]+\\s---$");

    public boolean containsCurrentTerm(String line, String currentTerm){
        String trimmed = line == null ? "" : line.trim();
        return termPattern.matcher(trimmed).find() && trimmed.equals(currentTerm);
    }
    public boolean containsTerm(String line){
        String trimmed = line == null ? "" : line.trim();
        return termPattern.matcher(trimmed).find();
    }

}
