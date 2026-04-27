/*
 * Copyright (c) 2025, Mantas
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package net.runelite.client.plugins.recorder.hotkey;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Frame;
import java.util.function.Consumer;

/** Tiny modal: a text field, focused on open. Enter -> consumer.accept(label).
 *  Esc -> cancel. The dialog itself emits no events — the recorder caller emits
 *  MarkerDialog open/close around showing it. */
public final class MarkerDialog
{
    public void show(Frame owner, Consumer<String> onAccept)
    {
        JDialog d = new JDialog(owner, "Marker label", true);
        d.setLayout(new BorderLayout(8, 8));
        JTextField f = new JTextField(28);
        d.add(f, BorderLayout.CENTER);
        JButton ok = new JButton("Add");
        d.add(ok, BorderLayout.EAST);
        Runnable accept = () -> {
            String v = f.getText() == null ? "" : f.getText().trim();
            if (!v.isEmpty()) onAccept.accept(v);
            d.dispose();
        };
        ok.addActionListener(e -> accept.run());
        f.addActionListener(e -> accept.run());
        f.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "cancel");
        f.getActionMap().put("cancel", new AbstractAction()
        {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { d.dispose(); }
        });
        d.pack();
        d.setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(f::requestFocusInWindow);
        d.setVisible(true);
    }
}
