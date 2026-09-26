package com.titan.titancorebanking.regulatory;

import com.jcraft.jsch.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;

@Slf4j
@Component
public class SftpUploader {

    @Value("${regulatory.sftp.host}")
    private String sftpHost;

    @Value("${regulatory.sftp.port:22}")
    private int sftpPort;

    @Value("${regulatory.sftp.username}")
    private String sftpUsername;

    @Value("${regulatory.sftp.password}")
    private String sftpPassword;

    @Value("${regulatory.sftp.remote-dir:/reports}")
    private String remoteDir;

    public void upload(File file, String fileName) throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession(sftpUsername, sftpHost, sftpPort);
        session.setPassword(sftpPassword);
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect();

        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect();

        try (FileInputStream fis = new FileInputStream(file)) {
            channel.cd(remoteDir);
            channel.put(fis, fileName);
            log.info("✅ Uploaded {} to SFTP", fileName);
        } finally {
            channel.disconnect();
            session.disconnect();
        }
    }
}
