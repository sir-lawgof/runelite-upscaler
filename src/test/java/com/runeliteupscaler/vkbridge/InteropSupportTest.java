package com.runeliteupscaler.vkbridge;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * S2-21: required GL extensions per platform; S2-20: UUID formatting.
 */
public class InteropSupportTest
{
	@Test
	public void s2_21_windowsNeedsWin32Variants()
	{
		assertEquals(Collections.emptyList(),
			InteropSupport.missingExtensions(InteropSupport.Platform.WINDOWS, true, true, true, true, false, false));
		assertEquals(Arrays.asList("GL_EXT_memory_object_win32", "GL_EXT_semaphore_win32"),
			InteropSupport.missingExtensions(InteropSupport.Platform.WINDOWS, true, true, false, false, true, true));
	}

	@Test
	public void s2_21_linuxNeedsFdVariants()
	{
		assertEquals(Collections.emptyList(),
			InteropSupport.missingExtensions(InteropSupport.Platform.LINUX, true, true, false, false, true, true));
		assertEquals(Arrays.asList("GL_EXT_memory_object_fd"),
			InteropSupport.missingExtensions(InteropSupport.Platform.LINUX, true, true, true, true, false, true));
	}

	@Test
	public void s2_21_baseExtensionsAlwaysRequired()
	{
		assertEquals(Arrays.asList("GL_EXT_memory_object", "GL_EXT_semaphore"),
			InteropSupport.missingExtensions(InteropSupport.Platform.WINDOWS, false, false, true, true, true, true));
	}

	@Test
	public void s2_20_uuidAsHex()
	{
		ByteBuffer b = ByteBuffer.allocate(4);
		b.put((byte) 0x00).put((byte) 0x0f).put((byte) 0xa0).put((byte) 0xff);
		assertEquals("000fa0ff", InteropSupport.hex(b));
	}
}
