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
package net.runelite.client.plugins.recorder.buffer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;

public class JsonlGzipWriterTest
{
	@Rule public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void writesGzippedJsonlAndCloses() throws Exception
	{
		Path file = tmp.newFile("events.jsonl.gz").toPath();
		try (JsonlGzipWriter w = new JsonlGzipWriter(file))
		{
			w.writeLine("{\"a\":1}");
			w.writeLine("{\"b\":2}");
		}
		try (BufferedReader r = new BufferedReader(new InputStreamReader(
			new GZIPInputStream(Files.newInputStream(file)))))
		{
			assertEquals("{\"a\":1}", r.readLine());
			assertEquals("{\"b\":2}", r.readLine());
			assertNull(r.readLine());
		}
	}

	@Test
	public void multipleAppendsThroughOneStreamRoundTrip() throws Exception
	{
		Path file = tmp.newFile("events.jsonl.gz").toPath();
		try (JsonlGzipWriter w = new JsonlGzipWriter(file))
		{
			for (int i = 0; i < 100; i++) w.writeLine("{\"i\":" + i + "}");
		}
		int count = 0;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(
			new GZIPInputStream(Files.newInputStream(file)))))
		{
			while (r.readLine() != null) count++;
		}
		assertEquals(100, count);
	}
}
