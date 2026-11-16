package com.douai.module.feature;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Standard JPEG + XMP MotionPhoto + appended MP4, used when an OEM only offers album side effects. */
final class PortableMotionPhoto {
    private static final byte[] XMP_HEADER = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII);
    private PortableMotionPhoto() { }
    static void write(File image, File video, File output) throws IOException {
        if (!image.isFile() || !video.isFile() || video.length() < 12 || video.length() > 512L * 1024 * 1024)
            throw new IOException("实况输入为空或过大");
        try (RandomAccessFile source = new RandomAccessFile(video, "r")) {
            source.seek(4); if (source.readInt() != 0x66747970) throw new IOException("实况动态部分不是 MP4");
        }
        String xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\""
                + " GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\" GCamera:MotionPhotoPresentationTimestampUs=\"-1\""
                + " GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\" GCamera:MicroVideoOffset=\"" + video.length() + "\">"
                + "<Container:Directory><rdf:Seq><rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Padding=\"0\"/></rdf:li>"
                + "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"" + video.length() + "\" Item:Padding=\"0\"/></rdf:li>"
                + "</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>";
        byte[] xml = xmp.getBytes(StandardCharsets.UTF_8);
        Files.createFile(output.toPath());
        boolean complete = false;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(image)));
             DataOutputStream target = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
            if (input.readUnsignedShort() != 0xffd8) throw new IOException("便携实况封装需要 JPEG 原图");
            target.writeShort(0xffd8); target.writeShort(0xffe1); target.writeShort(XMP_HEADER.length + xml.length + 2);
            target.write(XMP_HEADER); target.write(xml);
            while (true) {
                int marker = input.readUnsignedShort();
                if ((marker & 0xff00) != 0xff00) throw new IOException("JPEG 段格式无效");
                if (marker == 0xffd9) { target.writeShort(marker); break; }
                if (marker == 0xffda) {
                    target.writeShort(marker);
                    // Stop at the JPEG EOI marker: an input may already contain a prior motion tail.
                    int previous = -1, value;
                    boolean ended = false;
                    while ((value = input.read()) != -1) {
                        target.write(value);
                        if (previous == 0xff && value == 0xd9) { ended = true; break; }
                        previous = value;
                    }
                    if (!ended) throw new IOException("JPEG 缺少结束标记");
                    break;
                }
                if (marker == 0xff01 || marker >= 0xffd0 && marker <= 0xffd7) { target.writeShort(marker); continue; }
                int size = input.readUnsignedShort();
                if (size < 2) throw new IOException("JPEG 段长度无效");
                byte[] segment = new byte[size - 2]; input.readFully(segment);
                if (marker == 0xffe1 && prefix(segment, XMP_HEADER)) continue;
                target.writeShort(marker); target.writeShort(size); target.write(segment);
            }
            try (var movie = new FileInputStream(video)) { byte[] buffer = new byte[32 * 1024]; int count; while ((count = movie.read(buffer)) != -1) target.write(buffer, 0, count); }
            complete = true;
        } finally { if (!complete) Files.deleteIfExists(output.toPath()); }
        if (!isMotionPhoto(output)) { Files.deleteIfExists(output.toPath()); throw new IOException("实况封装验证失败"); }
    }
    static boolean isMotionPhoto(File file) {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.readUnsignedShort() != 0xffd8 || input.readUnsignedShort() != 0xffe1) return false;
            int length = input.readUnsignedShort() - 2;
            if (length < XMP_HEADER.length || length > 65531) return false;
            byte[] segment = new byte[length]; input.readFully(segment);
            if (!prefix(segment, XMP_HEADER)) return false;
            String xml = new String(segment, XMP_HEADER.length, length - XMP_HEADER.length, StandardCharsets.UTF_8);
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("GCamera:MicroVideoOffset=\"([0-9]+)\"").matcher(xml);
            if (!xml.contains("GCamera:MotionPhoto=\"1\"") || !match.find()) return false;
            long size = Long.parseLong(match.group(1)), start = input.length() - size;
            if (size < 12 || start <= length + XMP_HEADER.length || start + 8 > input.length()) return false;
            input.seek(start + 4); return input.readInt() == 0x66747970;
        } catch (IOException | RuntimeException invalid) { return false; }
    }
    private static boolean prefix(byte[] value, byte[] start) { if (value.length < start.length) return false; for (int index = 0; index < start.length; index++) if (value[index] != start[index]) return false; return true; }
}
