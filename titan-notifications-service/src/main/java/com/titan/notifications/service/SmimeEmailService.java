package com.titan.notifications.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.mail.smime.SMIMESignedGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Properties;

@Service
@Slf4j
public class SmimeEmailService {

    @Value("${spring.mail.host:smtp.titan.com}")
    private String smtpHost;

    @Value("${spring.mail.port:587}")
    private int smtpPort;

    public void sendSignedEmail(String to, String subject, String body, byte[] pdfAttachment) throws Exception {
        Session session = Session.getInstance(buildSmtpProps());

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("noreply@titan.com"));
        message.setRecipients(MimeMessage.RecipientType.TO, InternetAddress.parse(to));
        message.setSubject(subject);

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setContent(body, "text/html; charset=UTF-8");

        MimeMultipart multipart = new MimeMultipart();
        multipart.addBodyPart(textPart);

        if (pdfAttachment != null) {
            MimeBodyPart attachPart = new MimeBodyPart();
            attachPart.setContent(pdfAttachment, "application/pdf");
            attachPart.setFileName("statement.pdf");
            multipart.addBodyPart(attachPart);
        }

        message.setContent(multipart);

        MimeMessage signed = signMessage(message, session);

        javax.mail.Transport.send(signed);
        log.info("📧 Sent S/MIME signed email to {}", to);
    }

    private MimeMessage signMessage(MimeMessage message, Session session) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream("secrets/email-signing.p12")) {
            ks.load(fis, "titan123".toCharArray());
        }

        String alias = ks.aliases().nextElement();
        PrivateKey privateKey = (PrivateKey) ks.getKey(alias, "titan123".toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);

        SMIMESignedGenerator gen = new SMIMESignedGenerator();
        gen.addSignerInfoGenerator(
            new JcaSimpleSignerInfoGeneratorBuilder()
                .setProvider("BC")
                .build("SHA256withRSA", privateKey, cert)
        );
        gen.addCertificates(new JcaCertStore(List.of(cert)));

        javax.mail.internet.MimeMultipart signedMultipart = gen.generate(message);

        MimeMessage signedMessage = new MimeMessage(session);
        signedMessage.setFrom(message.getFrom()[0]);
        signedMessage.setRecipients(MimeMessage.RecipientType.TO, message.getRecipients(MimeMessage.RecipientType.TO));
        signedMessage.setSubject(message.getSubject());
        signedMessage.setContent(signedMultipart);
        signedMessage.saveChanges();
        return signedMessage;
    }

    private Properties buildSmtpProps() {
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", smtpPort);
        props.put("mail.smtp.starttls.enable", "true");
        return props;
    }
}
