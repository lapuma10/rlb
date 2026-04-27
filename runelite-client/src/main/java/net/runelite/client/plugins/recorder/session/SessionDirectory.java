/*
 * Copyright (c) 2024, RuneLite
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
package net.runelite.client.plugins.recorder.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Filesystem helper. Creates session directories, writes meta.json,
 *  renames the directory to embed the final intent label on stop. */
@Slf4j
public final class SessionDirectory
{
	private static final DateTimeFormatter DIR_TS = DateTimeFormatter
		.ofPattern("yyyy-MM-dd-HHmm").withZone(ZoneOffset.UTC);

	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Path rootDir;

	public SessionDirectory(Path rootDir)
	{
		this.rootDir = rootDir;
	}

	public RecordingSession create(Instant now) throws IOException
	{
		Files.createDirectories(rootDir);
		String sessionId = DIR_TS.format(now) + "-recording";
		Path dir = rootDir.resolve(sessionId);
		// Avoid collision if the same minute fires twice
		int n = 1;
		while (Files.exists(dir))
		{
			dir = rootDir.resolve(sessionId + "-" + n++);
		}
		Files.createDirectory(dir);
		return new RecordingSession(sessionId, dir, now);
	}

	public void writeMeta(RecordingSession session, MetaJson meta) throws IOException
	{
		Path file = session.getDirectory().resolve("meta.json");
		Files.writeString(file, PRETTY.toJson(meta));
	}

	/** Rename the session dir to embed the intent label. Returns the new path
	 *  (or the original path if the rename fails — non-fatal). */
	public Path renameWithIntent(RecordingSession session, String intentLabel)
	{
		if (intentLabel == null || intentLabel.isBlank()) return session.getDirectory();
		String slug = intentLabel.toLowerCase()
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
		if (slug.isEmpty()) return session.getDirectory();
		Path src = session.getDirectory();
		Path dst = src.resolveSibling(src.getFileName().toString().replace("-recording", "-" + slug));
		try
		{
			return Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException e)
		{
			log.warn("could not rename session dir to {}: {}", dst, e.toString());
			return src;
		}
	}

	public void openInFileBrowser(Path dir)
	{
		if (!Desktop.isDesktopSupported()) return;
		try { Desktop.getDesktop().open(dir.toFile()); }
		catch (IOException e) { log.warn("could not open recordings dir: {}", e.toString()); }
	}
}
