/*
 * Copyright (c) 2026, josephtschafer
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
package com.runeliteupscaler.temporal;

/**
 * S2-06: matrix math for the camera motion pass. Matrices are column-major float[16] (as uploaded to GL);
 * the math runs in double because the scene's world coordinates are large (about 1e4) and the
 * reprojection subtracts two nearly equal positions.
 */
public final class Reprojection
{
	private Reprojection()
	{
	}

	/**
	 * S2-06: the matrix taking this frame's clip/NDC position to last frame's clip position:
	 * {@code prevViewProj * inverse(viewProj)}. Works for points at infinite distance (sky, depth 0)
	 * because it never divides the world position by w.
	 *
	 * @return column-major float[16], or null if viewProj is not invertible
	 */
	public static float[] currentToPrevious(float[] viewProj, float[] prevViewProj)
	{
		double[] inv = invert(toDouble(viewProj));
		if (inv == null)
		{
			return null;
		}
		double[] m = multiply(toDouble(prevViewProj), inv);
		float[] out = new float[16];
		for (int i = 0; i < 16; i++)
		{
			out[i] = (float) m[i];
		}
		return out;
	}

	/**
	 * S2-06: reference implementation of the shader's math, for tests: motion in pixels (current to
	 * previous) for a pixel at NDC (x, y, z).
	 *
	 * @return {dx, dy} in pixels for a render target of the given size
	 */
	public static double[] motionPixels(float[] currentToPrevious, double ndcX, double ndcY, double ndcZ,
		int width, int height)
	{
		double[] m = toDouble(currentToPrevious);
		double px = m[0] * ndcX + m[4] * ndcY + m[8] * ndcZ + m[12];
		double py = m[1] * ndcX + m[5] * ndcY + m[9] * ndcZ + m[13];
		double pw = m[3] * ndcX + m[7] * ndcY + m[11] * ndcZ + m[15];
		double prevX = px / pw;
		double prevY = py / pw;
		return new double[]{(prevX - ndcX) * 0.5 * width, (prevY - ndcY) * 0.5 * height};
	}

	static double[] toDouble(float[] m)
	{
		double[] d = new double[16];
		for (int i = 0; i < 16; i++)
		{
			d[i] = m[i];
		}
		return d;
	}

	/** Column-major a * b. */
	static double[] multiply(double[] a, double[] b)
	{
		double[] r = new double[16];
		for (int col = 0; col < 4; col++)
		{
			for (int row = 0; row < 4; row++)
			{
				double s = 0;
				for (int k = 0; k < 4; k++)
				{
					s += a[k * 4 + row] * b[col * 4 + k];
				}
				r[col * 4 + row] = s;
			}
		}
		return r;
	}

	/** 4x4 inverse by Gauss-Jordan elimination with partial pivoting; null if singular. */
	static double[] invert(double[] m)
	{
		// Work on a row-major augmented copy [A | I]
		double[][] a = new double[4][8];
		for (int row = 0; row < 4; row++)
		{
			for (int col = 0; col < 4; col++)
			{
				a[row][col] = m[col * 4 + row];
			}
			a[row][4 + row] = 1;
		}

		for (int c = 0; c < 4; c++)
		{
			int pivot = c;
			for (int r = c + 1; r < 4; r++)
			{
				if (Math.abs(a[r][c]) > Math.abs(a[pivot][c]))
				{
					pivot = r;
				}
			}
			if (Math.abs(a[pivot][c]) < 1e-12)
			{
				return null;
			}
			double[] tmp = a[c];
			a[c] = a[pivot];
			a[pivot] = tmp;

			double p = a[c][c];
			for (int k = 0; k < 8; k++)
			{
				a[c][k] /= p;
			}
			for (int r = 0; r < 4; r++)
			{
				if (r != c && a[r][c] != 0)
				{
					double f = a[r][c];
					for (int k = 0; k < 8; k++)
					{
						a[r][k] -= f * a[c][k];
					}
				}
			}
		}

		double[] inv = new double[16];
		for (int row = 0; row < 4; row++)
		{
			for (int col = 0; col < 4; col++)
			{
				inv[col * 4 + row] = a[row][4 + col];
			}
		}
		return inv;
	}
}
