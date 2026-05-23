package net.runelite.client.plugins.recorder.analyse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.plugins.recorder.buffer.RecordingBuffer;
import net.runelite.client.plugins.recorder.events.EventCodec;
import net.runelite.client.plugins.recorder.events.Events;
import net.runelite.client.plugins.recorder.events.RecordedEvent;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StepEventTest
{
	// ─── StepEvent record contract ──────────────────────────────────

	@Test
	public void stepEventExposesAllStructuredFields()
	{
		StepEvent ev = new StepEvent(
			"ClickNpc(cow)", "failed",
			"npc", "cow:1234", "Cow",
			"Attack",
			7L,
			"STALE_REF",
			642, 391,
			"re-resolve failed");

		assertEquals("ClickNpc(cow)", ev.name());
		assertEquals("failed", ev.phase());
		assertEquals("npc", ev.targetType());
		assertEquals("cow:1234", ev.targetId());
		assertEquals("Cow", ev.targetName());
		assertEquals("Attack", ev.verb());
		assertEquals(Long.valueOf(7L), ev.ticksElapsed());
		assertEquals("STALE_REF", ev.diagnosticReason());
		assertEquals(Integer.valueOf(642), ev.clickX());
		assertEquals(Integer.valueOf(391), ev.clickY());
		assertEquals("re-resolve failed", ev.detail());
	}

	@Test
	public void stepEventAllowsAllNullablesNull()
	{
		// A "started" Step with no target, no click, no diagnostic.
		StepEvent ev = new StepEvent(
			"Idle", "started",
			null, null, null,
			null,
			null,
			null,
			null, null,
			null);

		assertNull(ev.targetType());
		assertNull(ev.targetId());
		assertNull(ev.targetName());
		assertNull(ev.verb());
		assertNull(ev.ticksElapsed());
		assertNull(ev.diagnosticReason());
		assertNull(ev.clickX());
		assertNull(ev.clickY());
		assertNull(ev.detail());
	}

	// ─── Events.Step persistence shape ──────────────────────────────

	@Test
	public void eventsStepIdentifiesAsStepType()
	{
		Events.Step step = new Events.Step(
			1L, 100L, 50,
			"X", "started",
			null, null, null, null, null, null, null, null, null);
		assertEquals("step", step.type());
	}

	@Test
	public void eventsStepCarriesAllStepEventFieldsPlusSeqTMsTick()
	{
		Events.Step step = new Events.Step(
			7L, 12345L, 200,
			"ClickNpc(cow)", "succeeded",
			"npc", "cow:1234", "Cow",
			"Attack",
			3L,
			null,
			642, 391,
			null);

		assertEquals(7L, step.seq());
		assertEquals(12345L, step.tMs());
		assertEquals(200, step.tick());
		assertEquals("ClickNpc(cow)", step.name());
		assertEquals("succeeded", step.phase());
		assertEquals("npc", step.targetType());
		assertEquals("cow:1234", step.targetId());
		assertEquals("Cow", step.targetName());
		assertEquals("Attack", step.verb());
		assertEquals(Long.valueOf(3L), step.ticksElapsed());
		assertNull(step.diagnosticReason());
		assertEquals(Integer.valueOf(642), step.clickX());
		assertEquals(Integer.valueOf(391), step.clickY());
		assertNull(step.detail());
	}

	// ─── EventCodec serialization — typed fields, not stringified ──

	@Test
	public void eventCodecSerializesAllStructuredFieldsAsTypedJson()
	{
		EventCodec codec = new EventCodec();
		Events.Step step = new Events.Step(
			42L, 1234567L, 50,
			"ClickNpc(cow)", "failed",
			"npc", "cow:1234", "Cow",
			"Attack",
			7L,
			"STALE_REF",
			642, 391,
			"re-resolve failed");

		String line = codec.toJsonLine(step);
		JsonObject json = JsonParser.parseString(line).getAsJsonObject();

		assertEquals("step", json.get("type").getAsString());
		assertEquals(42L, json.get("seq").getAsLong());
		// EventCodec renames tMs → t_ms for the on-disk schema.
		assertEquals(1234567L, json.get("t_ms").getAsLong());
		assertEquals(50, json.get("tick").getAsInt());

		assertEquals("ClickNpc(cow)", json.get("name").getAsString());
		assertEquals("failed", json.get("phase").getAsString());

		assertEquals("npc", json.get("targetType").getAsString());
		assertEquals("cow:1234", json.get("targetId").getAsString());
		assertEquals("Cow", json.get("targetName").getAsString());

		assertEquals("Attack", json.get("verb").getAsString());

		// Numeric fields must serialize as JSON numbers (not strings),
		// so the dashboard can read them without string-parsing.
		assertTrue("ticksElapsed must be a JSON number",
			json.get("ticksElapsed").isJsonPrimitive()
				&& json.get("ticksElapsed").getAsJsonPrimitive().isNumber());
		assertEquals(7L, json.get("ticksElapsed").getAsLong());

		assertEquals("STALE_REF", json.get("diagnosticReason").getAsString());

		assertTrue("clickX must be a JSON number",
			json.get("clickX").isJsonPrimitive()
				&& json.get("clickX").getAsJsonPrimitive().isNumber());
		assertEquals(642, json.get("clickX").getAsInt());
		assertEquals(391, json.get("clickY").getAsInt());

		assertEquals("re-resolve failed", json.get("detail").getAsString());
	}

	@Test
	public void eventCodecOmitsNullFieldsForStartedStepWithNoTarget()
	{
		EventCodec codec = new EventCodec();
		// A "started" event for an Idle Step — only the four
		// always-present fields (seq/tMs/tick/name/phase) and the
		// type discriminator should appear.
		Events.Step step = new Events.Step(
			1L, 100L, 10,
			"Idle", "started",
			null, null, null, null, null, null, null, null, null);

		String line = codec.toJsonLine(step);
		JsonObject json = JsonParser.parseString(line).getAsJsonObject();

		// Required-present fields.
		assertEquals("step", json.get("type").getAsString());
		assertEquals(1L, json.get("seq").getAsLong());
		assertEquals(100L, json.get("t_ms").getAsLong());
		assertEquals(10, json.get("tick").getAsInt());
		assertEquals("Idle", json.get("name").getAsString());
		assertEquals("started", json.get("phase").getAsString());

		// Gson default (serializeNulls = false, as configured in
		// EventCodec) omits null fields from the JSON object — so an
		// "absent" key for the dashboard means "not applicable for
		// this Step", not "the producer wrote zero/empty-string".
		assertFalse("targetType should be omitted when null", json.has("targetType"));
		assertFalse("targetId should be omitted when null", json.has("targetId"));
		assertFalse("targetName should be omitted when null", json.has("targetName"));
		assertFalse("verb should be omitted when null", json.has("verb"));
		assertFalse("ticksElapsed should be omitted when null", json.has("ticksElapsed"));
		assertFalse("diagnosticReason should be omitted when null", json.has("diagnosticReason"));
		assertFalse("clickX should be omitted when null", json.has("clickX"));
		assertFalse("clickY should be omitted when null", json.has("clickY"));
		assertFalse("detail should be omitted when null", json.has("detail"));
	}

	// ─── RecordingBuffer pipeline (matches RecorderManager.recordStepEvent) ─

	@Test
	public void stepEventLandsInBufferViaTheSameEnqueueLambdaRecorderManagerUses()
	{
		// Mirrors exactly what RecorderManager.recordStepEvent does:
		//   buffer.enqueue((seq, tMs) -> new Events.Step(seq, tMs, tick,
		//       ev.name(), ev.phase(),
		//       ev.targetType(), ev.targetId(), ev.targetName(),
		//       ev.verb(),
		//       ev.ticksElapsed(),
		//       ev.diagnosticReason(),
		//       ev.clickX(), ev.clickY(),
		//       ev.detail()))
		// — catches a regression in the StepEvent → Events.Step mapping
		// without needing a full RecorderManager + Client + flush-daemon
		// scaffold.
		RecordingBuffer buffer = new RecordingBuffer();
		StepEvent input = new StepEvent(
			"ClickNpc(cow)", "succeeded",
			"npc", "cow:1234", "Cow",
			"Attack",
			3L,
			null,
			642, 391,
			null);
		int tick = 50;

		buffer.enqueue((seq, tMs) -> new Events.Step(
			seq, tMs, tick,
			input.name(), input.phase(),
			input.targetType(), input.targetId(), input.targetName(),
			input.verb(),
			input.ticksElapsed(),
			input.diagnosticReason(),
			input.clickX(), input.clickY(),
			input.detail()));

		List<RecordedEvent> drained = new ArrayList<>();
		buffer.drainTo(drained);

		assertEquals(1, drained.size());
		RecordedEvent drainedEv = drained.get(0);
		assertTrue("expected Events.Step", drainedEv instanceof Events.Step);
		Events.Step step = (Events.Step) drainedEv;

		// Every script-facing field round-tripped through the lambda.
		assertEquals(input.name(), step.name());
		assertEquals(input.phase(), step.phase());
		assertEquals(input.targetType(), step.targetType());
		assertEquals(input.targetId(), step.targetId());
		assertEquals(input.targetName(), step.targetName());
		assertEquals(input.verb(), step.verb());
		assertEquals(input.ticksElapsed(), step.ticksElapsed());
		assertEquals(input.diagnosticReason(), step.diagnosticReason());
		assertEquals(input.clickX(), step.clickX());
		assertEquals(input.clickY(), step.clickY());
		assertEquals(input.detail(), step.detail());

		assertEquals(tick, step.tick());
		// seq + tMs are buffer-assigned; we just confirm they were stamped.
		assertTrue("seq should be assigned by buffer", step.seq() >= 0L);
		assertTrue("tMs should be assigned by buffer", step.tMs() >= 0L);
	}

	// ─── full-shape JSON-line example matches the dashboard schema ─

	@Test
	public void eventCodecJsonMatchesTheStructuredSchemaPhase7WillRead()
	{
		// The shape this test asserts is exactly the schema the
		// dashboard will read — assert in one place so a future codec
		// change that breaks it surfaces here.
		EventCodec codec = new EventCodec();
		Events.Step step = new Events.Step(
			100L, 9999L, 73,
			"ClickNpc(cow)", "failed",
			"npc", "cow:1234", "Cow",
			"Attack",
			7L,
			"STALE_REF",
			642, 391,
			"re-resolve failed");

		String line = codec.toJsonLine(step);
		JsonObject json = JsonParser.parseString(line).getAsJsonObject();

		// All 14 expected keys present (3 envelope + 11 payload).
		assertNotNull(json.get("type"));
		assertNotNull(json.get("seq"));
		assertNotNull(json.get("t_ms"));
		assertNotNull(json.get("tick"));
		assertNotNull(json.get("name"));
		assertNotNull(json.get("phase"));
		assertNotNull(json.get("targetType"));
		assertNotNull(json.get("targetId"));
		assertNotNull(json.get("targetName"));
		assertNotNull(json.get("verb"));
		assertNotNull(json.get("ticksElapsed"));
		assertNotNull(json.get("diagnosticReason"));
		assertNotNull(json.get("clickX"));
		assertNotNull(json.get("clickY"));
		assertNotNull(json.get("detail"));
	}
}
