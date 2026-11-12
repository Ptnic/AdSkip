package com.adship.adskip;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** JVM-only sanity test for the DNS wire codec. Run tools/run_selftest.ps1. */
public final class DnsCodecSelfTest {

    public static void main(String[] args) {
        testQuestionParsing();
        testSinkholeA();
        testSinkholeAaaa();
        testFailure();
        testMalformed();
        System.out.println("DnsCodec self-test: PASS");
    }

    private static void testQuestionParsing() {
        byte[] query = query("Ads.Example.COM", 1);
        DnsCodec.Question q = DnsCodec.parseQuestion(query);
        require(q != null, "question parsed");
        require("ads.example.com".equals(q.name), "name normalized");
        require(q.type == 1, "type A");
        require(q.endOffset == query.length, "question end");
    }

    private static void testSinkholeA() {
        byte[] query = query("ads.example.com", 1);
        byte[] response = DnsCodec.buildSinkhole(query);
        require(response != null, "sinkhole built");
        require(DnsCodec.u16(response, 0) == DnsCodec.u16(query, 0), "id preserved");
        require((DnsCodec.u16(response, 2) & 0x8000) != 0, "QR set");
        require((DnsCodec.u16(response, 2) & 0x000F) == 0, "NOERROR");
        require(DnsCodec.u16(response, 4) == 1, "one question");
        require(DnsCodec.u16(response, 6) == 1, "one answer");
        int answer = response.length - 16;
        require(DnsCodec.u16(response, answer) == 0xC00C, "answer name pointer");
        require(DnsCodec.u16(response, answer + 2) == 1, "answer type A");
        require(response[answer + 12] == 0 && response[answer + 13] == 0
                && response[answer + 14] == 0 && response[answer + 15] == 0, "0.0.0.0");
    }

    private static void testSinkholeAaaa() {
        byte[] query = query("tracker.example.com", 28);
        byte[] response = DnsCodec.buildSinkhole(query);
        require(response != null, "AAAA sinkhole built");
        require(DnsCodec.u16(response, 6) == 1, "one AAAA answer");
        int answer = response.length - 28;
        require(DnsCodec.u16(response, answer + 2) == 28, "answer type AAAA");
        for (int i = 0; i < 16; i++) {
            require(response[answer + 12 + i] == 0, "AAAA is ::");
        }
    }

    private static void testFailure() {
        byte[] query = query("broken.example.com", 1);
        byte[] response = DnsCodec.buildFailure(query, 2);
        require(response != null, "failure built");
        require((DnsCodec.u16(response, 2) & 0x000F) == 2, "SERVFAIL");
        require(DnsCodec.u16(response, 6) == 0, "no answers");
    }

    private static void testMalformed() {
        require(DnsCodec.parseQuestion(new byte[4]) == null, "short rejected");
        byte[] query = query("x.example.com", 1);
        byte[] truncated = Arrays.copyOf(query, 14);
        require(DnsCodec.parseQuestion(truncated) == null, "truncated rejected");
    }

    private static byte[] query(String name, int type) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x12);
        out.write(0x34);
        out.write(0x01);
        out.write(0x00);
        out.write(0);
        out.write(1);
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(0);
        for (String label : name.split("\\.")) {
            byte[] data = label.getBytes(StandardCharsets.US_ASCII);
            out.write(data.length);
            out.write(data, 0, data.length);
        }
        out.write(0);
        out.write((type >> 8) & 0xFF);
        out.write(type & 0xFF);
        out.write(0);
        out.write(1);
        return out.toByteArray();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
