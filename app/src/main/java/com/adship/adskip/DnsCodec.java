package com.adship.adskip;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Minimal DNS wire-format reader/writer. We only need the question name and enough structure
 * to synthesize an authoritative-looking sinkhole or an error reply.
 */
public final class DnsCodec {

    public static final int TYPE_A = 1;
    public static final int TYPE_AAAA = 28;

    private static final int FLAG_RESPONSE = 0x8000;
    private static final int FLAG_RECURSION_DESIRED = 0x0100;
    private static final int FLAG_RECURSION_AVAILABLE = 0x0080;
    private static final int FLAG_TRUNCATED = 0x0200;

    private DnsCodec() {
    }

    public static final class Question {
        public final String name;
        public final int type;
        public final int endOffset;

        Question(String name, int type, int endOffset) {
            this.name = name;
            this.type = type;
            this.endOffset = endOffset;
        }
    }

    public static Question parseQuestion(byte[] message) {
        if (message == null || message.length < 12) {
            return null;
        }
        int qdCount = u16(message, 4);
        if (qdCount < 1) {
            return null;
        }
        try {
            int[] cursor = new int[]{12};
            String name = readName(message, cursor);
            if (name == null || name.isEmpty()) {
                return null;
            }
            int pos = cursor[0];
            if (pos + 4 > message.length) {
                return null;
            }
            int type = u16(message, pos);
            int end = pos + 4;
            return new Question(name, type, end);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static byte[] buildSinkhole(byte[] query) {
        Question q = parseQuestion(query);
        if (q == null) {
            return buildFailure(query, 1);
        }
        if (q.type == TYPE_A) {
            return buildAnswer(query, q, new byte[4]);
        }
        if (q.type == TYPE_AAAA) {
            return buildAnswer(query, q, new byte[16]);
        }
        return buildResponse(query, q, FLAG_RESPONSE | FLAG_RECURSION_DESIRED
                | FLAG_RECURSION_AVAILABLE, 0, null);
    }

    public static byte[] buildFailure(byte[] query, int rcode) {
        Question q = parseQuestion(query);
        int flags = FLAG_RESPONSE | FLAG_RECURSION_DESIRED | FLAG_RECURSION_AVAILABLE
                | (rcode & 0x0F);
        return buildResponse(query, q, flags, 0, null);
    }

    public static byte[] buildTruncated(byte[] query) {
        Question q = parseQuestion(query);
        int flags = FLAG_RESPONSE | FLAG_RECURSION_DESIRED | FLAG_RECURSION_AVAILABLE
                | FLAG_TRUNCATED;
        return buildResponse(query, q, flags, 0, null);
    }

    private static byte[] buildAnswer(byte[] query, Question q, byte[] rdata) {
        int flags = FLAG_RESPONSE | FLAG_RECURSION_DESIRED | FLAG_RECURSION_AVAILABLE;
        return buildResponse(query, q, flags, 1, new Answer(q.type, rdata));
    }

    private static byte[] buildResponse(byte[] query, Question q, int flags, int answerCount,
                                        Answer answer) {
        if (query == null || query.length < 12) {
            return null;
        }
        int questionEnd = q == null ? 12 : Math.min(q.endOffset, query.length);
        int questionCount = q == null ? 0 : 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 + (questionEnd - 12)
                + (answer == null ? 0 : 16));
        writeShort(out, u16(query, 0));
        writeShort(out, flags);
        writeShort(out, questionCount);
        writeShort(out, answerCount);
        writeShort(out, 0);
        writeShort(out, 0);
        if (questionEnd > 12) {
            out.write(query, 12, questionEnd - 12);
        }
        if (answer != null && answerCount > 0) {
            writeShort(out, 0xC00C);
            writeShort(out, answer.type);
            writeShort(out, 1);
            writeInt(out, 60);
            writeShort(out, answer.data.length);
            out.write(answer.data, 0, answer.data.length);
        }
        return out.toByteArray();
    }

    private static final class Answer {
        final int type;
        final byte[] data;

        Answer(int type, byte[] data) {
            this.type = type;
            this.data = data;
        }
    }

    /** Returns a lower-case name without the trailing dot, or null for malformed input. */
    private static String readName(byte[] data, int[] cursor) {
        int pos = cursor[0];
        int jumps = 0;
        boolean jumped = false;
        StringBuilder name = new StringBuilder(64);
        while (true) {
            if (pos < 0 || pos >= data.length) {
                return null;
            }
            int length = data[pos] & 0xFF;
            if (length == 0) {
                pos++;
                if (!jumped) {
                    cursor[0] = pos;
                }
                break;
            }
            if ((length & 0xC0) == 0xC0) {
                if (pos + 1 >= data.length) {
                    return null;
                }
                int pointer = ((length & 0x3F) << 8) | (data[pos + 1] & 0xFF);
                if (!jumped) {
                    cursor[0] = pos + 2;
                    jumped = true;
                }
                if (++jumps > 20 || pointer >= data.length) {
                    return null;
                }
                pos = pointer;
                continue;
            }
            if ((length & 0xC0) != 0) {
                return null;
            }
            if (pos + 1 + length > data.length) {
                return null;
            }
            if (name.length() > 0) {
                name.append('.');
            }
            name.append(new String(data, pos + 1, length, StandardCharsets.US_ASCII));
            if (name.length() > 253) {
                return null;
            }
            pos += 1 + length;
        }
        if (name.length() > 0 && name.charAt(name.length() - 1) == '.') {
            name.setLength(name.length() - 1);
        }
        return name.toString().toLowerCase(Locale.ROOT);
    }

    static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
