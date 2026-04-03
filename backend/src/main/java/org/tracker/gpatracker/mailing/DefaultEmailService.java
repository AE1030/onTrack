package org.tracker.gpatracker.mailing;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

@Service
public class DefaultEmailService implements EmailService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultEmailService.class);

    private final Resend resend;
    private final SpringTemplateEngine templateEngine;

    public DefaultEmailService(Resend resend, SpringTemplateEngine templateEngine) {
        this.resend = resend;
        this.templateEngine = templateEngine;
    }

    @Async
    @Override
    public void sendMail(AbstractEmailContext email) throws MessagingException {

        logger.info("sendMail — to: {}, subject: {}", email.getTo(), email.getSubject());

        Context context = new Context();
        context.setVariables(email.getContext());
        String emailContent = templateEngine.process(email.getTemplateLocation(), context);

        CreateEmailOptions params = CreateEmailOptions.builder()
                .from(email.getFrom())
                .to(email.getTo())
                .subject(email.getSubject())
                .html(emailContent)
                .build();

        try {
            CreateEmailResponse response = resend.emails().send(params);
            logger.info("sendMail — sent successfully to: {}, id: {}", email.getTo(), response.getId());
        } catch (ResendException e) {
            logger.error("sendMail — failed to send to: {}", email.getTo(), e);
            throw new MessagingException("Failed to send email via Resend", e);
        }
    }
}