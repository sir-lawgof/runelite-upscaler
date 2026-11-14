package com.runeliteupscaler.upscale;

import com.runeliteupscaler.targets.Size;
import com.runeliteupscaler.targets.Texture;
import java.util.Arrays;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * T-U4: backend fallback order with mocked isSupported() results (A-04, G-13).
 */
public class UpscalerChainTest
{
	private static final GpuCaps CAPS = new GpuCaps("test", "4.6", true);

	/** A backend whose support is fixed by the test. */
	private static final class Fake implements Upscaler
	{
		private final String name;
		private final Support support;
		boolean closed;

		Fake(String name, Support support)
		{
			this.name = name;
			this.support = support;
		}

		@Override
		public String name()
		{
			return name;
		}

		@Override
		public Support isSupported(GpuCaps caps)
		{
			return support;
		}

		@Override
		public void resize(Size render, Size display)
		{
		}

		@Override
		public void resetHistory()
		{
		}

		@Override
		public void evaluate(FrameInputs in, Texture out)
		{
		}

		@Override
		public Vec2 jitter(int frameIndex)
		{
			return Vec2.ZERO;
		}

		@Override
		public float mipBias(Size render, Size display)
		{
			return 0;
		}

		@Override
		public void close()
		{
			closed = true;
		}
	}

	private final Fake dlss = new Fake("DLSS", Support.unsupported("not an RTX GPU"));
	private final Fake fsr2 = new Fake("FSR 2", Support.unsupported("missing GL_EXT_memory_object"));
	private final Fake fsr1 = new Fake("FSR 1", Support.ok());
	private final Fake bilinear = new Fake("Bilinear", Support.ok());

	private UpscalerChain chain()
	{
		return new UpscalerChain(Arrays.asList(dlss, fsr2, fsr1, bilinear));
	}

	@Test
	public void tU4_picksFirstSupportedInOrder()
	{
		UpscalerChain chain = chain();
		assertSame(fsr1, chain.select("DLSS", CAPS));
		assertSame(fsr1, chain.active());
	}

	@Test
	public void tU4_fallbackReasonNamesEverySkippedBackend()
	{
		UpscalerChain chain = chain();
		chain.select("DLSS", CAPS);
		assertEquals("DLSS: not an RTX GPU; FSR 2: missing GL_EXT_memory_object", chain.fallbackReason());
	}

	@Test
	public void tU4_requestedSupportedBackendHasNoFallbackReason()
	{
		UpscalerChain chain = chain();
		assertSame(fsr1, chain.select("FSR 1", CAPS));
		assertEquals("", chain.fallbackReason());
	}

	@Test
	public void tU4_selectionStartsAtRequestedBackend()
	{
		UpscalerChain chain = chain();
		assertSame(bilinear, chain.select("Bilinear", CAPS));
	}

	@Test
	public void tU4_unknownRequestStartsAtTop()
	{
		UpscalerChain chain = chain();
		assertSame(fsr1, chain.select("", CAPS));
	}

	@Test
	public void tU4_runtimeFailureFallsToNextBackend()
	{
		UpscalerChain chain = chain();
		chain.select("FSR 1", CAPS);
		chain.markFailed(fsr1);
		assertSame(bilinear, chain.select("FSR 1", CAPS));
		assertEquals("FSR 1: failed at runtime", chain.fallbackReason());
	}

	@Test
	public void tU4_noneSupportedReturnsNull()
	{
		UpscalerChain chain = new UpscalerChain(Arrays.asList(dlss, fsr2));
		assertNull(chain.select("DLSS", CAPS));
		assertNull(chain.active());
	}

	@Test
	public void tU4_closeClosesEveryBackend()
	{
		UpscalerChain chain = chain();
		chain.select("DLSS", CAPS);
		chain.close();
		assertTrue(dlss.closed && fsr2.closed && fsr1.closed && bilinear.closed);
		assertNull(chain.active());
	}
}
