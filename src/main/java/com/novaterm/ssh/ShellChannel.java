package com.novaterm.ssh;

import org.apache.sshd.client.channel.PtyCapableChannelSession;

import java.io.IOException;

public class ShellChannel implements AutoCloseable {
    private final PtyCapableChannelSession channel;

    ShellChannel(PtyCapableChannelSession channel) {
        this.channel = channel;
    }

    /** Send terminal resize notification to the remote host. */
    public void sendResize(int cols, int rows) throws IOException {
        channel.sendWindowChange(cols, rows);
    }

    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() throws Exception {
        channel.close(false);
    }
}
