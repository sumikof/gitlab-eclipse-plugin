package com.gitlab.eclipse.lsp.install;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal streaming reader for POSIX ustar archives. Supports exactly what the
 * gitlab-lsp release tarball needs: regular-file extraction by name; all other
 * entry types are surfaced so callers can skip them. Pure JDK: no OSGi/Eclipse
 * dependencies.
 */
public final class TarArchiveReader {
	public record Entry(String name, long size, boolean regularFile) {}

	private final InputStream in;
	private long remaining;
	private long padding;

	public TarArchiveReader(InputStream in) {
		this.in = in;
	}

	/** Advances to the next entry, skipping any unread data. Returns null at end of archive. */
	public Entry nextEntry() throws IOException {
		in.skipNBytes(remaining + padding);
		remaining = 0;
		padding = 0;

		byte[] header = new byte[512];
		if (!readBlock(header)) {
			return null;
		}
		if (isZeroBlock(header)) {
			return null;
		}
		String name = nulTerminated(header, 0, 100);
		String prefix = nulTerminated(header, 345, 155);
		if (!prefix.isEmpty()) {
			name = prefix + "/" + name;
		}
		long size = parseOctal(header, 124, 12);
		byte typeflag = header[156];
		remaining = size;
		padding = (512 - (size % 512)) % 512;
		return new Entry(name, size, typeflag == '0' || typeflag == 0);
	}

	/** Writes the current entry's data to {@code out}. */
	public void copyEntry(OutputStream out) throws IOException {
		byte[] buffer = new byte[8192];
		while (remaining > 0) {
			int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
			if (n < 0) {
				throw new EOFException("Truncated tar entry");
			}
			out.write(buffer, 0, n);
			remaining -= n;
		}
	}

	private boolean readBlock(byte[] block) throws IOException {
		int off = 0;
		while (off < block.length) {
			int n = in.read(block, off, block.length - off);
			if (n < 0) {
				if (off == 0) {
					return false;
				}
				throw new EOFException("Truncated tar header");
			}
			off += n;
		}
		return true;
	}

	private static boolean isZeroBlock(byte[] block) {
		for (byte b : block) {
			if (b != 0) {
				return false;
			}
		}
		return true;
	}

	private static String nulTerminated(byte[] block, int offset, int length) {
		int end = offset;
		while (end < offset + length && block[end] != 0) {
			end++;
		}
		return new String(block, offset, end - offset, StandardCharsets.US_ASCII);
	}

	private static long parseOctal(byte[] block, int offset, int length) {
		long value = 0;
		for (int i = offset; i < offset + length; i++) {
			byte b = block[i];
			if (b == 0 || b == ' ') {
				continue;
			}
			value = (value << 3) + (b - '0');
		}
		return value;
	}
}
