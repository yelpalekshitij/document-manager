package app.vaultdoc.core.auth.mail;

public interface EmailSender {

    void sendPlainText(String to, String subject, String body);
}
