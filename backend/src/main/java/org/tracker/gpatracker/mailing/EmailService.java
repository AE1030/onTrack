package org.tracker.gpatracker.mailing;

import jakarta.mail.MessagingException;

public interface EmailService {
    void sendMail(final AbstractEmailContext emailContext) throws MessagingException;
}