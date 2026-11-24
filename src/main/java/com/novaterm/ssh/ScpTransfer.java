package com.novaterm.ssh;

import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.scp.client.DefaultScpClientCreator;
import org.apache.sshd.scp.client.ScpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * SCP file transfer using Apache MINA SSHD's ScpClient.
 */
public class ScpTransfer {
    private static final Logger log = LoggerFactory.getLogger(ScpTransfer.class);

    public static void upload(ClientSession cs, Path localFile, String remotePath,
                               Runnable onDone, java.util.function.Consumer<Exception> onError) {
        Thread.ofVirtual().start(() -> {
            try {
                ScpClient scp = DefaultScpClientCreator.INSTANCE.createScpClient(cs);
                scp.upload(localFile, remotePath, ScpClient.Option.Recursive);
                log.info("SCP upload: {} → {}", localFile, remotePath);
                if (onDone != null) onDone.run();
            } catch (Exception e) {
                log.error("SCP upload failed", e);
                if (onError != null) onError.accept(e);
            }
        });
    }

    public static void download(ClientSession cs, String remotePath, Path localDir,
                                 Runnable onDone, java.util.function.Consumer<Exception> onError) {
        Thread.ofVirtual().start(() -> {
            try {
                ScpClient scp = DefaultScpClientCreator.INSTANCE.createScpClient(cs);
                scp.download(remotePath, localDir, ScpClient.Option.Recursive);
                log.info("SCP download: {} → {}", remotePath, localDir);
                if (onDone != null) onDone.run();
            } catch (Exception e) {
                log.error("SCP download failed", e);
                if (onError != null) onError.accept(e);
            }
        });
    }
}
