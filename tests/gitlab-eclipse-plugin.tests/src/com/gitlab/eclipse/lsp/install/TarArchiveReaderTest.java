package com.gitlab.eclipse.lsp.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class TarArchiveReaderTest {

	// --- ustar test-archive builder -------------------------------------

	private static byte[] header(String name, long size, char typeflag) {
		byte[] h = new byte[512];
		byte[] n = name.getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(n, 0, h, 0, n.length);
		writeOctal(h, 100, 8, 0644);          // mode
		writeOctal(h, 108, 8, 0);             // uid
		writeOctal(h, 116, 8, 0);             // gid
		writeOctal(h, 124, 12, size);         // size
		writeOctal(h, 136, 12, 0);            // mtime
		h[156] = (byte) typeflag;
		System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 6);
		h[263] = '0';
		h[264] = '0';
		Arrays.fill(h, 148, 156, (byte) ' '); // checksum field = spaces while summing
		int sum = 0;
		for (byte b : h) sum += b & 0xff;
		byte[] cks = String.format("%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(cks, 0, h, 148, 8);
		return h;
	}

	/** Recomputes the header checksum after fields have been edited. */
	private static void rechecksum(byte[] h) {
		Arrays.fill(h, 148, 156, (byte) ' ');
		int sum = 0;
		for (byte b : h) sum += b & 0xff;
		System.arraycopy(String.format("%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII), 0, h, 148, 8);
	}

	private static void writeOctal(byte[] h, int offset, int length, long value) {
		byte[] s = String.format("%0" + (length - 1) + "o", value).getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(s, 0, h, offset, s.length);
	}

	static byte[] fileEntry(String name, byte[] content) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(header(name, content.length, '0'));
		out.writeBytes(content);
		out.writeBytes(new byte[(512 - (content.length % 512)) % 512]);
		return out.toByteArray();
	}

	static byte[] dirEntry(String name) {
		return header(name, 0, '5');
	}

	static byte[] archive(byte[]... entries) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] e : entries) out.writeBytes(e);
		out.writeBytes(new byte[1024]); // end-of-archive marker
		return out.toByteArray();
	}

	// --- tests ----------------------------------------------------------

	@Test
	void readsFileEntriesInOrder() throws IOException {
		byte[] a = "hello".getBytes(StandardCharsets.UTF_8);
		byte[] b = new byte[600]; // spans two data blocks
		Arrays.fill(b, (byte) 7);
		var tar = new TarArchiveReader(new ByteArrayInputStream(
				archive(fileEntry("./bin/a.txt", a), fileEntry("./bin/b.dat", b))));

		var first = tar.nextEntry();
		assertEquals("./bin/a.txt", first.name());
		assertEquals(5, first.size());
		assertTrue(first.regularFile());
		var out = new ByteArrayOutputStream();
		tar.copyEntry(out);
		assertArrayEquals(a, out.toByteArray());

		var second = tar.nextEntry();
		assertEquals("./bin/b.dat", second.name());
		assertEquals(600, second.size());
		out = new ByteArrayOutputStream();
		tar.copyEntry(out);
		assertArrayEquals(b, out.toByteArray());

		assertNull(tar.nextEntry());
	}

	@Test
	void skipsUnreadDataWhenAdvancing() throws IOException {
		byte[] big = new byte[1500];
		var tar = new TarArchiveReader(new ByteArrayInputStream(
				archive(fileEntry("skipme", big), fileEntry("wanted", "x".getBytes(StandardCharsets.UTF_8)))));
		tar.nextEntry(); // do not copy data
		var second = tar.nextEntry();
		assertEquals("wanted", second.name());
		var out = new ByteArrayOutputStream();
		tar.copyEntry(out);
		assertEquals("x", out.toString(StandardCharsets.UTF_8));
	}

	@Test
	void flagsNonRegularEntries() throws IOException {
		var tar = new TarArchiveReader(new ByteArrayInputStream(archive(dirEntry("./bin/"))));
		var entry = tar.nextEntry();
		assertEquals("./bin/", entry.name());
		assertFalse(entry.regularFile());
		assertNull(tar.nextEntry());
	}

	@Test
	void rejectsGnuLongNameEntries() throws IOException {
		byte[] longName = "some/very/long/path\0".getBytes(StandardCharsets.US_ASCII);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(header("././@LongLink", longName.length, 'L'));
		out.writeBytes(longName);
		out.writeBytes(new byte[(512 - (longName.length % 512)) % 512]);
		out.writeBytes(fileEntry("some/very/long/path", "x".getBytes(StandardCharsets.UTF_8)));
		var tar = new TarArchiveReader(new ByteArrayInputStream(archive(out.toByteArray())));
		assertThrows(IOException.class, tar::nextEntry);
	}

	@Test
	void rejectsPaxExtendedHeaderEntries() {
		byte[] pax = "27 path=some/very/long/path\n".getBytes(StandardCharsets.US_ASCII);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(header("PaxHeaders.0/file", pax.length, 'x'));
		out.writeBytes(pax);
		out.writeBytes(new byte[(512 - (pax.length % 512)) % 512]);
		var tar = new TarArchiveReader(new ByteArrayInputStream(archive(out.toByteArray())));
		assertThrows(IOException.class, tar::nextEntry);
	}

	@Test
	void rejectsBase256SizeEncoding() {
		byte[] h = header("big.bin", 0, '0');
		h[124] = (byte) 0x80; // GNU base-256 size marker
		rechecksum(h);
		var tar = new TarArchiveReader(new ByteArrayInputStream(archive(h)));
		assertThrows(IOException.class, tar::nextEntry);
	}

	@Test
	void joinsUstarPrefixField() throws IOException {
		byte[] h = header("file.txt", 0, '0');
		byte[] prefix = "some/long/dir".getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(prefix, 0, h, 345, prefix.length);
		rechecksum(h);

		var tar = new TarArchiveReader(new ByteArrayInputStream(archive(h)));
		assertEquals("some/long/dir/file.txt", tar.nextEntry().name());
	}
}
