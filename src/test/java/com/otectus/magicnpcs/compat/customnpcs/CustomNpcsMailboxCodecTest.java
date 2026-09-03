package com.otectus.magicnpcs.compat.customnpcs;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mailbox encoding, on its own. It is the part of the script bridge most likely to be quietly
 * wrong — a dropped {@code seq}, an argument silently read as zero — and the only part that can be
 * exercised without CustomNPCs, Iron's and a running server.
 */
class CustomNpcsMailboxCodecTest {

    private static final UUID TARGET = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static Map<String, Object> request(String op, Object seq, Object... args) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(CustomNpcsMailboxCodec.KEY_OP, op);
        if (seq != null) {
            data.put(CustomNpcsMailboxCodec.KEY_SEQ, seq);
        }
        for (int i = 0; i < args.length; i += 2) {
            data.put(CustomNpcsMailboxCodec.ARG_PREFIX + args[i], args[i + 1]);
        }
        return data;
    }

    @Test
    void everyOperationSurvivesTheRoundTripWithItsArguments() {
        for (String op : CustomNpcsMailboxCodec.OPS) {
            CustomNpcsMailboxCodec.Request decoded =
                    CustomNpcsMailboxCodec.decodeRequest(request(op, 7,
                            CustomNpcsMailboxCodec.ARG_SCHOOL, "irons_spellbooks:fire",
                            CustomNpcsMailboxCodec.ARG_SPELL, "irons_spellbooks:fireball",
                            CustomNpcsMailboxCodec.ARG_LEVEL, 3,
                            CustomNpcsMailboxCodec.ARG_TARGET, TARGET.toString(),
                            CustomNpcsMailboxCodec.ARG_SUSPENDED, 1));
            assertEquals(op, decoded.op(), op + " must decode as itself");
            assertTrue(decoded.isKnownOp(), op + " is in OPS, so it must decode as a known op");
            assertFalse(decoded.isEmpty());
            assertEquals(7, decoded.seq().intValue(), "the sequence number must be echoed exactly");
            assertEquals("irons_spellbooks:fire", decoded.string(CustomNpcsMailboxCodec.ARG_SCHOOL));
            assertEquals("irons_spellbooks:fireball", decoded.string(CustomNpcsMailboxCodec.ARG_SPELL));
            assertEquals(3, decoded.integer(CustomNpcsMailboxCodec.ARG_LEVEL, 1));
            assertEquals(TARGET, decoded.uuid(CustomNpcsMailboxCodec.ARG_TARGET));
            assertTrue(decoded.flag(CustomNpcsMailboxCodec.ARG_SUSPENDED));
        }
    }

    @Test
    void aLevelWrittenAsAScriptNumberStillReadsAsThatLevel() {
        // Script engines have one number type. A level stored as 3.0 must not become 0, and must not
        // render as "3.0" when it is passed on as a string.
        CustomNpcsMailboxCodec.Request decoded = CustomNpcsMailboxCodec.decodeRequest(
                request("cast", 1.0, CustomNpcsMailboxCodec.ARG_LEVEL, 3.0));
        assertEquals(3, decoded.integer(CustomNpcsMailboxCodec.ARG_LEVEL, 1));
        assertEquals("3", decoded.string(CustomNpcsMailboxCodec.ARG_LEVEL));
        assertEquals(1, decoded.seq().intValue());
    }

    @Test
    void aValueThatIsNotAStringOrANumberIsDroppedRatherThanGuessedAt() {
        Map<String, Object> data = request("setCastingSuspended", 2,
                CustomNpcsMailboxCodec.ARG_SUSPENDED, Boolean.TRUE,
                CustomNpcsMailboxCodec.ARG_SPELL, List.of("irons_spellbooks:fireball"));
        CustomNpcsMailboxCodec.Request decoded = CustomNpcsMailboxCodec.decodeRequest(data);
        assertTrue(decoded.args().isEmpty(),
                "a boxed Boolean and a list are values the codec cannot state a meaning for; they must "
                        + "not reach the API as a guess");
        assertFalse(decoded.flag(CustomNpcsMailboxCodec.ARG_SUSPENDED));
        assertNull(decoded.string(CustomNpcsMailboxCodec.ARG_SPELL));
    }

    @Test
    void anUnknownOperationDecodesAsItselfSoItCanBeRefusedByName() {
        CustomNpcsMailboxCodec.Request decoded =
                CustomNpcsMailboxCodec.decodeRequest(request("getSchoolName", 3));
        assertFalse(decoded.isEmpty(), "the request exists; it is the op that is wrong");
        assertFalse(decoded.isKnownOp());
        assertEquals("getSchoolName", decoded.op(),
                "the caller answers INVALID_ARGUMENT and needs to name what was asked for");
    }

    @Test
    void dataWithNoOpKeyIsNoRequestAtAll() {
        assertTrue(CustomNpcsMailboxCodec.decodeRequest(Map.of()).isEmpty());
        assertTrue(CustomNpcsMailboxCodec.decodeRequest(Map.of("unrelated.key", "value")).isEmpty());
        // An op key holding a number is not a request either: there is no operation it could name.
        assertTrue(CustomNpcsMailboxCodec.decodeRequest(
                Map.of(CustomNpcsMailboxCodec.KEY_OP, 5)).isEmpty());
        assertTrue(CustomNpcsMailboxCodec.decodeRequest(
                Map.of(CustomNpcsMailboxCodec.KEY_OP, "   ")).isEmpty());
    }

    @Test
    void requestKeysNamesEveryRequestKeyAndNothingElse() {
        Map<String, Object> data = request("canCast", 4,
                CustomNpcsMailboxCodec.ARG_SPELL, "irons_spellbooks:fireball",
                CustomNpcsMailboxCodec.ARG_LEVEL, 2);
        data.put("someone.elses.key", "leave me alone");
        data.put(CustomNpcsMailboxCodec.RESULT_CODE, 0);
        List<String> keys = CustomNpcsMailboxCodec.requestKeys(data);
        assertEquals(List.of(
                        CustomNpcsMailboxCodec.KEY_OP,
                        CustomNpcsMailboxCodec.KEY_SEQ,
                        CustomNpcsMailboxCodec.ARG_PREFIX + CustomNpcsMailboxCodec.ARG_SPELL,
                        CustomNpcsMailboxCodec.ARG_PREFIX + CustomNpcsMailboxCodec.ARG_LEVEL),
                keys,
                "the bridge removes exactly these before executing; removing a result or another mod's "
                        + "key would be someone else's data gone");
    }

    @Test
    void anAnswerIsEncodedWithItsSequenceCodeAndMessage() {
        Map<String, Object> encoded = CustomNpcsMailboxCodec.encodeResult(9,
                CustomNpcsScriptApi.Result.ok("irons_spellbooks:fire"));
        assertEquals(9, ((Number) encoded.get(CustomNpcsMailboxCodec.RESULT_SEQ)).intValue());
        assertEquals(CustomNpcsScriptApi.ResultCode.OK.ordinal(),
                encoded.get(CustomNpcsMailboxCodec.RESULT_CODE));
        assertEquals("ok", encoded.get(CustomNpcsMailboxCodec.RESULT_MESSAGE));
        assertEquals("irons_spellbooks:fire", encoded.get(CustomNpcsMailboxCodec.RESULT_VALUE));
    }

    @Test
    void aBooleanAnswerIsEncodedAsOneOrZero() {
        // Not a Boolean: a script engine reading a boxed Boolean back out of another mod's data map
        // compares it far less reliably than it compares a number.
        assertEquals(1, CustomNpcsMailboxCodec.encodeResult(1, CustomNpcsScriptApi.Result.ok(true))
                .get(CustomNpcsMailboxCodec.RESULT_VALUE));
        assertEquals(0, CustomNpcsMailboxCodec.encodeResult(1, CustomNpcsScriptApi.Result.ok(false))
                .get(CustomNpcsMailboxCodec.RESULT_VALUE));
    }

    @Test
    void aRefusalCarriesNoValueKeyAtAll() {
        Map<String, Object> encoded = CustomNpcsMailboxCodec.encodeResult(null,
                CustomNpcsScriptApi.Result.no(CustomNpcsScriptApi.ResultCode.NO_MANA, "not enough mana"));
        assertFalse(encoded.containsKey(CustomNpcsMailboxCodec.RESULT_VALUE),
                "an absent value must be absent, not a null the script has to test for");
        assertEquals(CustomNpcsScriptApi.ResultCode.NO_MANA.ordinal(),
                encoded.get(CustomNpcsMailboxCodec.RESULT_CODE));
        assertEquals(0, ((Number) encoded.get(CustomNpcsMailboxCodec.RESULT_SEQ)).intValue());
    }

    @Test
    void theResultCodeSurvivesTheRoundTripToItsEnum() {
        for (CustomNpcsScriptApi.ResultCode code : CustomNpcsScriptApi.ResultCode.values()) {
            CustomNpcsScriptApi.Result result = CustomNpcsScriptApi.Result.no(code, code.name());
            assertEquals(code, result.codeAsEnum());
            assertEquals(code == CustomNpcsScriptApi.ResultCode.OK, result.isOk());
            // The bean aliases are what a script engine actually sees.
            assertEquals(result.code(), result.getCode());
            assertEquals(result.message(), result.getMessage());
            assertEquals(result.value(), result.getValue());
        }
    }
}
