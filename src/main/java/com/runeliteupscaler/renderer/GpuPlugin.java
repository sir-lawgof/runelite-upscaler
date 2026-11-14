/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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
package com.runeliteupscaler.renderer;

import com.google.common.base.Stopwatch;
import com.google.common.primitives.Ints;
import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import javax.inject.Inject;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.FloatProjection;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.TextureProvider;
import net.runelite.api.TileObject;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import com.runeliteupscaler.renderer.config.AntiAliasingMode;
import com.runeliteupscaler.renderer.config.UIScalingMode;
import com.runeliteupscaler.renderer.template.Template;
import com.runeliteupscaler.debug.DebugView;
import com.runeliteupscaler.debug.DebugViews;
import com.runeliteupscaler.debug.GlObjects;
import com.runeliteupscaler.temporal.CameraMotionPass;
import com.runeliteupscaler.temporal.HistoryReset;
import com.runeliteupscaler.temporal.Jitter;
import com.runeliteupscaler.temporal.Reprojection;
import com.runeliteupscaler.temporal.TemporalState;
import com.runeliteupscaler.debug.GpuTimers;
import com.runeliteupscaler.debug.RenderStats;
import com.runeliteupscaler.debug.UpscalerDebugOverlay;
import com.runeliteupscaler.targets.RenderSize;
import com.runeliteupscaler.targets.RenderTargets;
import com.runeliteupscaler.targets.Size;
import com.runeliteupscaler.targets.TargetFormat;
import com.runeliteupscaler.targets.Texture;
import com.runeliteupscaler.upscale.BilinearUpscaler;
import com.runeliteupscaler.upscale.Fsr1Upscaler;
import com.runeliteupscaler.upscale.UpscalerBackend;
import com.runeliteupscaler.upscale.FrameInputs;
import com.runeliteupscaler.upscale.GpuCaps;
import com.runeliteupscaler.upscale.Upscaler;
import com.runeliteupscaler.upscale.UpscalerChain;
import com.runeliteupscaler.upscale.Vec2;
import java.util.List;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_SOURCE_API;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_OTHER;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_PERFORMANCE;
import static org.lwjgl.opengl.GL43C.glDebugMessageControl;
import static org.lwjgl.opengl.GL45C.GL_ZERO_TO_ONE;
import static org.lwjgl.opengl.GL45C.glClipControl;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.Configuration;

@PluginDescriptor(
	name = "Upscaler GPU",
	configName = "UpscalerGpuPlugin",
	description = "Offloads rendering to GPU",
	tags = {"fog", "draw distance"},
	// G-10, D-06: only one renderer may own DrawCallbacks. RuneLite turns the other plugin off
	// when either side is enabled (117 HD also declares conflicts = "GPU").
	conflicts = {"GPU", "117 HD"},
	loadInSafeMode = false
)
@Slf4j
public class GpuPlugin extends Plugin implements DrawCallbacks
{
	static final int MAX_DISTANCE = 184;
	static final int MAX_FOG_DEPTH = 100;
	static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2; // offset for sxy -> msxy
	private static final int UNIFORM_BUFFER_SIZE = 5 * Float.BYTES;
	private static final int NUM_ZONES = Constants.EXTENDED_SCENE_SIZE >> 3;
	private static final int MAX_WORLDVIEWS = 4096;

	@Inject
	private Client client;

	@Inject
	private ClientUI clientUI;

	@Inject
	private ClientThread clientThread;

	@Inject
	private GpuPluginConfig config;

	@Inject
	private TextureManager textureManager;

	@Inject
	private RegionManager regionManager;

	@Inject
	private DrawManager drawManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	private Canvas canvas;
	private AWTContext awtContext;
	private Callback debugCallback;

	private boolean lwjglInitted = false;
	private GLCapabilities glCapabilities;

	static final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vert.glsl")
		.add(GL_FRAGMENT_SHADER, "frag.glsl");

	static final Shader UI_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vertui.glsl")
		.add(GL_FRAGMENT_SHADER, "fragui.glsl");

	static int glProgram;
	private int glUiProgram;

	private int interfaceTexture;
	private int interfacePbo;

	private int vaoUiHandle;
	private int vboUiHandle;

	// S1-02, A-02: off-screen scene target at render resolution (replaces the canvas-sized fboScene)
	private final RenderTargets renderTargets = new RenderTargets();
	private boolean sceneFboValid;
	private int aaSamples;
	// S1-02, S1-11: where the scene goes on the window, in device pixels (GL coords, origin bottom-left).
	// Set together with the render targets in preSceneDrawToplevel so the two always match.
	private int displayX, displayY, displayWidth, displayHeight;
	// Color the scene pass cleared to. The stock plugin cleared the whole canvas-sized FBO and blitted all
	// of it, so the window outside the 3D viewport showed this color; draw() keeps that for parity (G-30).
	private float sceneClearR, sceneClearG, sceneClearB;

	// A-01, A-04, A-05: upscaler backends, selected in fallback order
	private UpscalerChain upscalerChain;
	private GpuCaps gpuCaps;
	private final FrameInputs frameInputs = new FrameInputs();

	// G-31: debug overlay
	private final RenderStats renderStats = new RenderStats();
	// A-06: GPU time per stage; makes no GL calls unless the debug overlay is on
	private final GpuTimers gpuTimers = new GpuTimers(renderStats);
	// Step 2: temporal state (S2-05, S2-12) and debug views (G-32)
	private final TemporalState temporalState = new TemporalState();
	private final DebugViews debugViews = new DebugViews();
	private final CameraMotionPass cameraMotionPass = new CameraMotionPass(); // S2-06
	private final HistoryReset historyReset = new HistoryReset(); // S2-08
	private int lastTargetDisplayWidth, lastTargetDisplayHeight;
	// S2-09: last LOD bias set on the texture array (reapplied if the array is recreated)
	private float lastMipBias;
	private int lastMipBiasTextureArray = -1;
	/** Q-07: true when glClipControl(ZERO_TO_ONE) is active, so window depth = 100 / z_view */
	private boolean depthZeroToOne;
	// S1-15: GL_NVX_gpu_memory_info, read about once a second while the overlay is shown
	private static final int GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX = 0x9048;
	private static final int GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX = 0x9049;
	private int gpuMemoryFrame;
	private UpscalerDebugOverlay debugOverlay;

	@Inject
	private OverlayManager overlayManager;

	private int textureArrayId;

	private final GLBuffer glUniformBuffer = new GLBuffer("uniform buffer");

	private int lastCanvasWidth;
	private int lastCanvasHeight;
	private AntiAliasingMode lastAntiAliasingMode;
	private int lastAnisotropicFilteringLevel = -1;

	private GpuFloatBuffer uniformBuffer;

	private int cameraYaw, cameraPitch;

	static class RenderThread
	{
		VAOList vaoO, vaoA;
		float[] tmp = new float[3];
		ModelUploader modelUploader;
	}

	private RenderThread[] rts;

	private SceneUploader clientUploader, mapUploader;

	static class SceneContext
	{
		final float[] projection = Mat4.identity();

		final int sizeX, sizeZ;
		Zone[][] zones;

		private int cameraX, cameraY, cameraZ;
		private int minLevel, level, maxLevel;
		private Set<Integer> hideRoofIds;

		SceneContext(int sizeX, int sizeZ)
		{
			this.sizeX = sizeX;
			this.sizeZ = sizeZ;
			zones = new Zone[sizeX][sizeZ];
			for (int x = 0; x < sizeX; ++x)
			{
				for (int z = 0; z < sizeZ; ++z)
				{
					zones[x][z] = new Zone();
				}
			}
		}

		void free()
		{
			for (int x = 0; x < sizeX; ++x)
			{
				for (int z = 0; z < sizeZ; ++z)
				{
					zones[x][z].free();
				}
			}
		}
	}

	SceneContext context(Scene scene)
	{
		int wvid = scene.getWorldViewId();
		if (wvid == WorldView.TOPLEVEL)
		{
			return root;
		}
		return subs[wvid];
	}

	SceneContext context(WorldView wv)
	{
		int wvid = wv.getId();
		if (wvid == WorldView.TOPLEVEL)
		{
			return root;
		}
		return subs[wvid];
	}

	private SceneContext root;
	private SceneContext[] subs;
	private Zone[][] nextZones;
	private Map<Integer, Integer> nextRoofChanges;

	// Uniforms
	private int uniUseFog;
	private int uniFogColor;
	private int uniFogDepth;
	private int uniDrawDistance;
	private int uniExpandedMapLoadingChunks;
	private int uniSmoothBanding;
	private int uniWorldProj;
	static int uniEntityProj;
	static int uniEntityTint;
	private int uniBrightness;
	private int uniTex;
	private int uniTexSourceDimensions;
	private int uniTexTargetDimensions;
	private int uniUiAlphaOverlay;
	private int uniTextures;
	private int uniTextureAnimations;
	private int uniBlockMain;
	private int uniTextureLightMode;
	private int uniTick;
	private int uniColorblindIntensity;
	private int uniUiColorblindIntensity;
	static int uniBase;

	static final float[] IDENTITY = Mat4.identity();

	@Override
	protected void startUp()
	{
		root = new SceneContext(NUM_ZONES, NUM_ZONES);
		subs = new SceneContext[MAX_WORLDVIEWS];
		int numThreads = config.numThreads();
		rts = new RenderThread[numThreads + 1];
		for (int i = 0; i < rts.length; ++i)
		{
			var rt = rts[i] = new RenderThread();
			rt.modelUploader = new ModelUploader();
		}
		clientUploader = new SceneUploader(renderCallbackManager);
		mapUploader = new SceneUploader(renderCallbackManager);
		debugOverlay = new UpscalerDebugOverlay(renderStats, config::upscalerDebugOverlay); // G-31
		overlayManager.add(debugOverlay);
		clientThread.invoke(() ->
		{
			// G-10: refuse to start if another renderer already owns DrawCallbacks (for example a
			// renderer plugin that does not declare a conflict with "GPU").
			DrawCallbacks owner = client.getDrawCallbacks();
			if (owner != null && owner != this)
			{
				refuseStart(owner);
				return true;
			}

			try
			{
				lastAnisotropicFilteringLevel = -1;

				AWTContext.loadNatives();

				canvas = client.getCanvas();

				synchronized (canvas.getTreeLock())
				{
					if (!canvas.isValid())
					{
						return false;
					}

					awtContext = new AWTContext(canvas);
					awtContext.configurePixelFormat(0, 0, 0);
				}

				awtContext.createGLContext();

				canvas.setIgnoreRepaint(true);

				// lwjgl defaults to lwjgl- + user.name, but this breaks if the username would cause an invalid path
				// to be created.
				Configuration.SHARED_LIBRARY_EXTRACT_DIRECTORY.set("lwjgl-rl");

				glCapabilities = GL.createCapabilities();

				log.info("Using device: {}", glGetString(GL_RENDERER));
				log.info("Using driver: {}", glGetString(GL_VERSION));

				if (!glCapabilities.OpenGL33)
				{
					throw new RuntimeException("OpenGL 3.3 is required but not available");
				}

				lwjglInitted = true;
				depthZeroToOne = glCapabilities.OpenGL45; // Q-07: glClipControl below is only called with GL 4.5

				// A-04: backends in fallback order: FSR 1 (S1-06), then bilinear (S1-05) as the final fallback
				gpuCaps = new GpuCaps(glGetString(GL_RENDERER), glGetString(GL_VERSION), glCapabilities.OpenGL43);
				upscalerChain = new UpscalerChain(List.of(new Fsr1Upscaler(), new BilinearUpscaler()));
				selectUpscaler();

				checkGLErrors();
				if (log.isDebugEnabled() && glCapabilities.glDebugMessageControl != 0)
				{
					debugCallback = GLUtil.setupDebugMessageCallback();
					if (debugCallback != null)
					{
						// [LWJGL] OpenGL debug message
						//	ID: 0x20071
						//	Source: API
						//	Type: OTHER
						//	Severity: NOTIFICATION
						//	Message: Buffer detailed info: Buffer object 2 (bound to GL_PIXEL_UNPACK_BUFFER_ARB, usage hint is GL_STREAM_DRAW) has been mapped WRITE_ONLY in SYSTEM HEAP memory (fast).
						glDebugMessageControl(GL_DEBUG_SOURCE_API, GL_DEBUG_TYPE_OTHER,
							GL_DONT_CARE, 0x20071, false);

						// [LWJGL] OpenGL debug message
						//	ID: 0x20052
						//	Source: API
						//	Type: PERFORMANCE
						//	Severity: MEDIUM
						//	Message: Pixel-path performance warning: Pixel transfer is synchronized with 3D rendering.
						glDebugMessageControl(GL_DEBUG_SOURCE_API, GL_DEBUG_TYPE_PERFORMANCE,
							GL_DONT_CARE, 0x20052, false);
					}
				}

				setupSyncMode();

				initBuffers();
				initVao();
				initProgram();
				initInterfaceTexture();
				if (glCapabilities.OpenGL45)
				{
					glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE); // 1 near 0 far
				}

				client.setDrawCallbacks(this);
				setupGpuFlags();
				client.setExpandedMapLoading(config.expandedMapLoadingZones());

				// force rebuild of main buffer provider to enable alpha channel
				client.resizeCanvas();

				lastCanvasWidth = lastCanvasHeight = -1;
				lastAntiAliasingMode = null;

				textureArrayId = -1;

				if (client.getGameState() == GameState.LOGGED_IN)
				{
					startupWorldLoad();
				}

				checkGLErrors();
			}
			catch (Throwable e)
			{
				log.error("Error starting GPU plugin", e);

				SwingUtilities.invokeLater(() ->
				{
					try
					{
						pluginManager.setPluginEnabled(this, false);
						pluginManager.stopPlugin(this);
					}
					catch (PluginInstantiationException ex)
					{
						log.error("error stopping plugin", ex);
					}
				});

				shutDown();
			}
			return true;
		});
	}

	// G-10: turn this plugin back off and tell the user which renderer is in the way.
	private void refuseStart(DrawCallbacks owner)
	{
		String ownerName = owner.getClass().getName();
		for (Plugin p : pluginManager.getPlugins())
		{
			if (p == owner)
			{
				ownerName = p.getName();
				break;
			}
		}

		log.warn("Not starting Upscaler GPU: renderer {} already owns DrawCallbacks", ownerName);
		final String message = "Upscaler GPU was not started because another renderer is active: "
			+ ownerName + ".\nTurn that plugin off, then turn Upscaler GPU on again.";

		SwingUtilities.invokeLater(() ->
		{
			try
			{
				pluginManager.setPluginEnabled(this, false);
				pluginManager.stopPlugin(this);
			}
			catch (PluginInstantiationException ex)
			{
				log.error("error stopping plugin", ex);
			}
			JOptionPane.showMessageDialog(client.getCanvas(), message, "Upscaler GPU", JOptionPane.WARNING_MESSAGE);
		});
	}

	private void setupGpuFlags()
	{
		int cpus = Runtime.getRuntime().availableProcessors();
		int threads = Math.min(cpus - 1, config.numThreads());
		log.debug("Using {} render threads", threads);
		client.setGpuFlags(DrawCallbacks.GPU
			| (config.removeVertexSnapping() ? DrawCallbacks.NO_VERTEX_SNAPPING : 0)
			| DrawCallbacks.ZBUF
			| DrawCallbacks.RENDER_THREADS(threads)
		);
	}

	private void startupWorldLoad()
	{
		WorldView root = client.getTopLevelWorldView();
		Scene scene = root.getScene();
		loadScene(root, scene);
		swapScene(scene);

		for (WorldEntity subEntity : root.worldEntities())
		{
			WorldView sub = subEntity.getWorldView();
			log.debug("WorldView loading: {}", sub.getId());
			loadSubScene(sub, sub.getScene());
			swapSub(sub.getScene());
		}
	}

	@Override
	protected void shutDown()
	{
		if (debugOverlay != null)
		{
			overlayManager.remove(debugOverlay); // G-31
			debugOverlay = null;
		}

		clientThread.invoke(() ->
		{
			// G-10: only reset the client's renderer state if this plugin owns it, so a refused
			// start (or a stop after another renderer took over) does not break the active renderer.
			if (client.getDrawCallbacks() == this)
			{
				client.setGpuFlags(0);
				client.setDrawCallbacks(null);
				client.setUnlockedFps(false);
				client.setExpandedMapLoading(0);
			}

			if (lwjglInitted)
			{
				if (textureArrayId != -1)
				{
					textureManager.freeTextureArray(textureArrayId);
					textureArrayId = -1;
				}

				root.free();

				shutdownInterfaceTexture();
				shutdownProgram();
				shutdownVao();
				shutdownBuffers();
				if (upscalerChain != null)
				{
					upscalerChain.close(); // A-07, G-11
					upscalerChain = null;
				}
				gpuTimers.close(); // A-06, G-11
				debugViews.close(); // G-32, G-11
				cameraMotionPass.close(); // S2-06, G-11
				temporalState.reset();
				renderTargets.close(); // A-02, G-11

				// S1-15, G-11: everything this plugin's code created must be gone by now
				int live = GlObjects.total();
				if (live != 0)
				{
					log.warn("GL objects still live after shutdown: {} ({})", live, GlObjects.breakdown());
				}
				else
				{
					log.debug("All upscaler GL objects freed on shutdown");
				}
			}

			if (awtContext != null)
			{
				awtContext.destroy();
				awtContext = null;
			}

			if (debugCallback != null)
			{
				debugCallback.free();
				debugCallback = null;
			}

			glCapabilities = null;

			// force main buffer provider rebuild to turn off alpha channel
			client.resizeCanvas();
		});
	}

	@Provides
	GpuPluginConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GpuPluginConfig.class);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (configChanged.getGroup().equals(GpuPluginConfig.GROUP))
		{
			if (configChanged.getKey().equals("unlockFps")
				|| configChanged.getKey().equals("vsyncMode")
				|| configChanged.getKey().equals("fpsTarget"))
			{
				log.debug("Rebuilding sync mode");
				clientThread.invokeLater(this::setupSyncMode);
			}
			else if (configChanged.getKey().equals("expandedMapLoadingChunks"))
			{
				clientThread.invokeLater(() ->
				{
					client.setExpandedMapLoading(config.expandedMapLoadingZones());
					if (client.getGameState() == GameState.LOGGED_IN)
					{
						client.setGameState(GameState.LOADING);
					}
				});
			}
			else if (configChanged.getKey().equals("upscalerBackend"))
			{
				// S1-13, G-33: switch backend live
				clientThread.invokeLater(() ->
				{
					if (upscalerChain != null)
					{
						selectUpscaler();
					}
				});
			}
			else if (configChanged.getKey().equals("removeVertexSnapping"))
			{
				log.debug("Toggle {}", configChanged.getKey());
				setupGpuFlags();
			}
			else if (configChanged.getKey().equals("uiScalingMode") || configChanged.getKey().equals("colorBlindMode"))
			{
				clientThread.invokeLater(() ->
				{
					log.debug("Recompiling shaders");
					shutdownProgram();
					initProgram();
				});
			}
			else if (configChanged.getKey().equals("numThreads"))
			{
				clientThread.invokeLater(() ->
				{
					for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
					{
						rts[i].vaoO.free();
						rts[i].vaoA.free();
					}

					int numThreads = config.numThreads();
					rts = new RenderThread[numThreads + 1];
					for (int i = 0; i < rts.length; ++i)
					{
						var rt = new RenderThread();
						rt.modelUploader = new ModelUploader();
						rt.vaoO = new VAOList(i > 0);
						rt.vaoA = new VAOList(i > 0);
						rts[i] = rt;
					}

					setupGpuFlags();
				});
			}
		}
	}

	private void setupSyncMode()
	{
		final boolean unlockFps = config.unlockFps();
		client.setUnlockedFps(unlockFps);

		// Without unlocked fps, the client manages sync on its 20ms timer
		GpuPluginConfig.SyncMode syncMode = unlockFps
			? this.config.syncMode()
			: GpuPluginConfig.SyncMode.OFF;

		int swapInterval = 0;
		switch (syncMode)
		{
			case ON:
				swapInterval = 1;
				break;
			case OFF:
				swapInterval = 0;
				break;
			case ADAPTIVE:
				swapInterval = -1;
				break;
		}

		int actualSwapInterval = awtContext.setSwapInterval(swapInterval);
		if (actualSwapInterval != swapInterval)
		{
			log.info("unsupported swap interval {}, got {}", swapInterval, actualSwapInterval);
		}

		client.setUnlockedFpsTarget(actualSwapInterval == 0 ? config.fpsTarget() : 0);
		checkGLErrors();
	}

	private Template createTemplate()
	{
		Template template = new Template();
		template.add(key ->
		{
			switch (key)
			{
				case "texture_config":
					return "#define TEXTURE_COUNT " + TextureManager.TEXTURE_COUNT + "\n";
				case "sampling_mode":
					return "#define SAMPLING_MODE " + config.uiScalingMode().ordinal() + "\n";
				case "colorblind_mode":
					return "#define COLORBLIND_MODE " + config.colorBlindMode().ordinal() + "\n";
			}
			return null;
		});
		template.addInclude(GpuPlugin.class);
		return template;
	}

	private void initProgram() throws ShaderException
	{
		// macOS core profile has no default VAO, so the shaders won't validate unless a VAO is bound
		glBindVertexArray(vaoUiHandle);

		Template template = createTemplate();
		glProgram = PROGRAM.compile(template);
		glUiProgram = UI_PROGRAM.compile(template);

		glBindVertexArray(0);

		initUniforms();
	}

	private void initUniforms()
	{
		uniWorldProj = glGetUniformLocation(glProgram, "worldProj");
		uniEntityProj = glGetUniformLocation(glProgram, "entityProj");
		uniEntityTint = glGetUniformLocation(glProgram, "entityTint");
		uniSmoothBanding = glGetUniformLocation(glProgram, "smoothBanding");
		uniBrightness = glGetUniformLocation(glProgram, "brightness");
		uniUseFog = glGetUniformLocation(glProgram, "useFog");
		uniFogColor = glGetUniformLocation(glProgram, "fogColor");
		uniFogDepth = glGetUniformLocation(glProgram, "fogDepth");
		uniDrawDistance = glGetUniformLocation(glProgram, "drawDistance");
		uniExpandedMapLoadingChunks = glGetUniformLocation(glProgram, "expandedMapLoadingChunks");
		uniTextureLightMode = glGetUniformLocation(glProgram, "textureLightMode");
		uniTick = glGetUniformLocation(glProgram, "tick");
		uniBlockMain = glGetUniformBlockIndex(glProgram, "uniforms");
		uniTextures = glGetUniformLocation(glProgram, "textures");
		uniTextureAnimations = glGetUniformLocation(glProgram, "textureAnimations");
		uniBase = glGetUniformLocation(glProgram, "base");
		uniColorblindIntensity = glGetUniformLocation(glProgram, "colorblindIntensity");

		uniTex = glGetUniformLocation(glUiProgram, "tex");
		uniTexTargetDimensions = glGetUniformLocation(glUiProgram, "targetDimensions");
		uniTexSourceDimensions = glGetUniformLocation(glUiProgram, "sourceDimensions");
		uniUiAlphaOverlay = glGetUniformLocation(glUiProgram, "alphaOverlay");
		uniUiColorblindIntensity = glGetUniformLocation(glUiProgram, "colorblindIntensity");
	}

	private void shutdownProgram()
	{
		glDeleteProgram(glProgram);
		glProgram = 0;

		glDeleteProgram(glUiProgram);
		glUiProgram = 0;
	}

	private void initVao()
	{
		// Create UI VAO
		vaoUiHandle = glGenVertexArrays();
		// Create UI buffer
		vboUiHandle = glGenBuffers();
		glBindVertexArray(vaoUiHandle);

		FloatBuffer vboUiBuf = GpuFloatBuffer.allocateDirect(5 * 4);
		vboUiBuf.put(new float[]{
			// positions     // texture coords
			1f, 1f, 0f, 1f, 0f, // top right
			1f, -1f, 0f, 1f, 1f, // bottom right
			-1f, -1f, 0f, 0f, 1f, // bottom left
			-1f, 1f, 0f, 0f, 0f  // top left
		});
		vboUiBuf.rewind();
		glBindBuffer(GL_ARRAY_BUFFER, vboUiHandle);
		glBufferData(GL_ARRAY_BUFFER, vboUiBuf, GL_STATIC_DRAW);

		// position attribute
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * Float.BYTES, 0);
		glEnableVertexAttribArray(0);

		// texture coord attribute
		glVertexAttribPointer(1, 2, GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
		glEnableVertexAttribArray(1);

		// unbind VAO/VBO
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	private void shutdownVao()
	{
		glDeleteBuffers(vboUiHandle);
		vboUiHandle = 0;

		glDeleteVertexArrays(vaoUiHandle);
		vaoUiHandle = 0;
	}

	private void initBuffers()
	{
		uniformBuffer = new GpuFloatBuffer(UNIFORM_BUFFER_SIZE);
		initGlBuffer(glUniformBuffer);
		Zone.initBuffer();

		for (int i = 0; i < rts.length; ++i)
		{
			rts[i].vaoO = new VAOList(i > 0);
			rts[i].vaoA = new VAOList(i > 0);
		}
	}

	private void initGlBuffer(GLBuffer glBuffer)
	{
		glBuffer.glBufferId = glGenBuffers();
	}

	private void shutdownBuffers()
	{
		destroyGlBuffer(glUniformBuffer);
		uniformBuffer = null;
		Zone.freeBuffer();

		for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
		{
			if (rts[i].vaoO != null)
			{
				rts[i].vaoO.free();
				rts[i].vaoO = null;
			}
			if (rts[i].vaoA != null)
			{
				rts[i].vaoA.free();
				rts[i].vaoA = null;
			}
		}
	}

	private void destroyGlBuffer(GLBuffer glBuffer)
	{
		if (glBuffer.glBufferId != -1)
		{
			glDeleteBuffers(glBuffer.glBufferId);
			glBuffer.glBufferId = -1;
		}
		glBuffer.size = -1;
	}

	private void initInterfaceTexture()
	{
		interfacePbo = glGenBuffers();

		interfaceTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	private void shutdownInterfaceTexture()
	{
		glDeleteBuffers(interfacePbo);
		glDeleteTextures(interfaceTexture);
		interfaceTexture = -1;
	}

	@Override
	public void preSceneDraw(Scene scene, Projection entityProjection,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		ctx.cameraX = (int) cameraX;
		ctx.cameraY = (int) cameraY;
		ctx.cameraZ = (int) cameraZ;
		ctx.minLevel = minLevel;
		ctx.level = level;
		ctx.maxLevel = maxLevel;
		ctx.hideRoofIds = hideRoofIds;

		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
			{
				rts[i].vaoO.map();
				rts[i].vaoA.map();
			}

			this.cameraYaw = client.getCameraYaw();
			this.cameraPitch = client.getCameraPitch();
			preSceneDrawToplevel(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw);
		}
		else
		{
			System.arraycopy(((FloatProjection) entityProjection).getProjection(), 0, ctx.projection, 0, 16);
			glUniformMatrix4fv(uniEntityProj, false, ctx.projection);
			glUniform4i(uniEntityTint, scene.getOverrideHue(), scene.getOverrideSaturation(), scene.getOverrideLuminance(), scene.getOverrideAmount());
		}
	}

	private void preSceneDrawToplevel(Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw)
	{
		gpuTimers.begin(GpuTimers.Stage.SCENE); // A-06: stage 1, scene pass
		scene.setDrawDistance(getDrawDistance());

		// UBO
		uniformBuffer.clear();
		uniformBuffer
			.put(cameraYaw)
			.put(cameraPitch)
			.put(cameraX)
			.put(cameraY)
			.put(cameraZ);
		uniformBuffer.flip();

		glBindBuffer(GL_UNIFORM_BUFFER, glUniformBuffer.glBufferId);
		glBufferData(GL_UNIFORM_BUFFER, uniformBuffer.getBuffer(), GL_DYNAMIC_DRAW);
		glBindBuffer(GL_UNIFORM_BUFFER, 0);
		uniformBuffer.clear();

		glBindBufferBase(GL_UNIFORM_BUFFER, 0, glUniformBuffer.glBufferId);

		checkGLErrors();

		final int canvasHeight = client.getCanvasHeight();
		final int canvasWidth = client.getCanvasWidth();

		final int viewportHeight = client.getViewportHeight();
		final int viewportWidth = client.getViewportWidth();

		// Setup anisotropic filtering
		final int anisotropicFilteringLevel = config.anisotropicFilteringLevel();

		if (textureArrayId != -1 && lastAnisotropicFilteringLevel != anisotropicFilteringLevel)
		{
			textureManager.setAnisotropicFilteringLevel(textureArrayId, anisotropicFilteringLevel);
			lastAnisotropicFilteringLevel = anisotropicFilteringLevel;
		}

		// Setup viewport
		int renderWidthOff = client.getViewportXOffset();
		int renderHeightOff = client.getViewportYOffset();
		int renderCanvasHeight = canvasHeight;
		int renderViewportHeight = viewportHeight;
		int renderViewportWidth = viewportWidth;
		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			renderCanvasHeight = dim.height;

			double scaleFactorY = dim.getHeight() / canvasHeight;
			double scaleFactorX = dim.getWidth() / canvasWidth;

			// Pad the viewport a little because having ints for our viewport dimensions can introduce off-by-one errors.
			final int padding = 1;

			// Ceil the sizes because even if the size is 599.1 we want to treat it as size 600 (i.e. render to the x=599 pixel).
			renderViewportHeight = (int) Math.ceil(scaleFactorY * (renderViewportHeight)) + padding * 2;
			renderViewportWidth = (int) Math.ceil(scaleFactorX * (renderViewportWidth)) + padding * 2;

			// Floor the offsets because even if the offset is 4.9, we want to render to the x=4 pixel anyway.
			renderHeightOff = (int) Math.floor(scaleFactorY * (renderHeightOff)) - padding;
			renderWidthOff = (int) Math.floor(scaleFactorX * (renderWidthOff)) - padding;
		}

		// S1-02, S1-11: the 3D viewport rectangle on the window, in device pixels. This is exactly the rectangle the
		// stock plugin passed to glDpiAwareViewport; the scene is now drawn off-screen and placed here in draw().
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform transform = graphicsConfiguration.getDefaultTransform();
		displayX = getScaledValue(transform.getScaleX(), renderWidthOff);
		displayY = getScaledValue(transform.getScaleY(), renderCanvasHeight - renderViewportHeight - renderHeightOff);
		displayWidth = Math.max(1, getScaledValue(transform.getScaleX(), renderViewportWidth));
		displayHeight = Math.max(1, getScaledValue(transform.getScaleY(), renderViewportHeight));

		// S1-02, A-03: render resolution = display viewport size x scale
		// S1-13: backend Off renders at native resolution (G-30)
		final int scalePercent = config.upscalerBackend() == UpscalerBackend.OFF ? 100
			: config.renderScale().percent(config.customRenderScale());
		final int renderWidth = RenderSize.scale(displayWidth, scalePercent);
		final int renderHeight = RenderSize.scale(displayHeight, scalePercent);

		// Setup anti-aliasing (same sample selection as the stock plugin)
		final AntiAliasingMode antiAliasingMode = config.antiAliasingMode();
		if (lastAntiAliasingMode != antiAliasingMode)
		{
			// Bind default FBO to check whether anti-aliasing is forced
			glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
			final int forcedAASamples = glGetInteger(GL_SAMPLES);
			final int maxSamples = glGetInteger(GL_MAX_SAMPLES);
			aaSamples = forcedAASamples != 0 ? forcedAASamples :
				Math.min(antiAliasingMode.getSamples(), maxSamples);

			log.debug("AA samples: {}, max samples: {}, forced samples: {}", aaSamples, maxSamples, forcedAASamples);
			lastAntiAliasingMode = antiAliasingMode;
		}

		// A-02: recreate the scene target when the render size or sample count changes (window resize, scale change)
		// S1-03: target color format, switched live by the development flag
		final TargetFormat targetFormat = config.highPrecisionTargets() ? TargetFormat.RGBA16F : TargetFormat.RGBA8;
		// S2-01, S2-02, D-08: temporal inputs need a sampleable depth texture and no MSAA
		final boolean temporal = config.temporalInputs();
		final int samples = temporal ? 0 : aaSamples;
		if (renderTargets.ensure(renderWidth, renderHeight, displayWidth, displayHeight, samples, targetFormat, temporal))
		{
			log.debug("Scene target {}x{} ({}x MSAA{}) for display {}x{} at {}%",
				renderWidth, renderHeight, samples, temporal ? ", temporal" : "", displayWidth, displayHeight, scalePercent);
			// S2-08: a new display size is a resize; otherwise the render size (scale) or mode changed
			historyReset.request(displayWidth != lastTargetDisplayWidth || displayHeight != lastTargetDisplayHeight
				? HistoryReset.Reason.RESIZE : HistoryReset.Reason.SCALE_CHANGE);
			lastTargetDisplayWidth = displayWidth;
			lastTargetDisplayHeight = displayHeight;
			// A-02: new sizes also resize the backend and reset its history
			Upscaler upscaler = upscalerChain.active();
			if (upscaler != null)
			{
				upscaler.resize(new Size(renderWidth, renderHeight), new Size(displayWidth, displayHeight));
			}
			// Reset (as the stock initFbo did): framebuffers back to the default
			glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		}

		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, renderTargets.sceneFbo());
		glViewport(0, 0, renderWidth, renderHeight);

		// G-31
		Upscaler active = upscalerChain.active();
		renderStats.backend = !renderTargets.isScaled() ? "Native (no upscale)"
			: active != null ? active.name() : "none";
		renderStats.fallbackReason = upscalerChain.fallbackReason();
		renderStats.scalePercent = scalePercent;
		renderStats.renderWidth = renderWidth;
		renderStats.renderHeight = renderHeight;
		renderStats.displayWidth = displayWidth;
		renderStats.displayHeight = displayHeight;
		renderStats.msaaSamples = samples;
		renderStats.msaaForcedOff = temporal && aaSamples > 0; // S2-01
		renderStats.targetFormat = targetFormat.toString();

		glUseProgram(glProgram);

		// Setup uniforms
		final int drawDistance = getDrawDistance();
		final int fogDepth = config.fogDepth();
		final int sky = client.getSkyboxColor();
		glUniform1i(uniUseFog, fogDepth > 0 ? 1 : 0);
		glUniform4f(uniFogColor, (sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
		glUniform1i(uniFogDepth, fogDepth);
		glUniform1i(uniDrawDistance, drawDistance * Perspective.LOCAL_TILE_SIZE);
		glUniform1i(uniExpandedMapLoadingChunks, client.getExpandedMapLoading());
		glUniform1f(uniColorblindIntensity, config.colorBlindIntensity());

		// Brightness happens to also be stored in the texture provider, so we use that
		TextureProvider textureProvider = client.getTextureProvider();
		glUniform1f(uniBrightness, (float) textureProvider.getBrightness());
		glUniform1f(uniSmoothBanding, config.smoothBanding() ? 0f : 1f);
		glUniform1f(uniTextureLightMode, config.brightTextures() ? 1f : 0f);
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			// avoid textures animating during loading
			glUniform1i(uniTick, client.getGameCycle() & 127);
		}

		// Calculate projection matrix
		float[] projectionMatrix = Mat4.scale(client.getScale(), client.getScale(), 1);
		Mat4.mul(projectionMatrix, Mat4.projection(viewportWidth, viewportHeight, 50));
		Mat4.mul(projectionMatrix, Mat4.rotateX(cameraPitch));
		Mat4.mul(projectionMatrix, Mat4.rotateY(cameraYaw));
		Mat4.mul(projectionMatrix, Mat4.translate(-cameraX, -cameraY, -cameraZ));

		// S2-05, S2-12: this and last frame's unjittered view-projection, frame index and frame time (A-05)
		float[] sceneProjection = projectionMatrix;
		final boolean jitterOn = temporal && config.jitterTest(); // D-08: stands in for an active temporal backend
		if (temporal)
		{
			if (temporalState.frameIndex() < 0)
			{
				historyReset.request(HistoryReset.Reason.PLUGIN_ENABLE); // S2-08: temporal inputs just turned on
			}
			temporalState.beginFrame(projectionMatrix, System.nanoTime());
			frameInputs.viewProj = temporalState.viewProj();
			frameInputs.prevViewProj = temporalState.prevViewProj();
			frameInputs.frameTimeMs = temporalState.frameTimeMs();
			renderStats.frameIndex = temporalState.frameIndex();
			renderStats.frameTimeMs = temporalState.frameTimeMs();

			// S2-03, S2-04: jitter the scene projection only. Motion uses the unjittered matrices (S2-06), and the
			// UI and RuneLite overlays use the client's own projection, so they don't shake.
			if (jitterOn)
			{
				int phases = Jitter.phaseCount(renderWidth, displayWidth);
				Vec2 jitter = Jitter.offset(temporalState.frameIndex(), phases);
				sceneProjection = Jitter.apply(projectionMatrix, jitter, renderWidth, renderHeight);
				frameInputs.jitter = jitter;
				renderStats.jitterX = jitter.x;
				renderStats.jitterY = jitter.y;
				renderStats.jitterPhase = (int) (temporalState.frameIndex() % phases);
				renderStats.jitterPhaseCount = phases;
			}
			else
			{
				frameInputs.jitter = Vec2.ZERO;
				renderStats.jitterPhaseCount = 0;
			}

			// S2-08: camera jumps count as cuts; consume this frame's reset, if any
			historyReset.cameraPosition(cameraX, cameraY, cameraZ);
			HistoryReset.Reason reset = historyReset.consume();
			frameInputs.reset = reset != null;
			if (reset != null)
			{
				log.debug("History reset: {}", reset.label);
				if (active != null)
				{
					active.resetHistory();
				}
			}
			renderStats.resetCount = historyReset.resetCount();
			renderStats.lastReset = historyReset.lastReason() == null ? "-" : historyReset.lastReason().label;
		}
		else if (temporalState.frameIndex() >= 0)
		{
			temporalState.reset();
			historyReset.forgetCamera();
			frameInputs.viewProj = frameInputs.prevViewProj = null;
			frameInputs.frameTimeMs = 0;
			frameInputs.jitter = null;
			frameInputs.reset = false;
			renderStats.frameIndex = -1;
			renderStats.jitterPhaseCount = 0;
		}
		glUniformMatrix4fv(uniWorldProj, false, sceneProjection);

		// S2-09: texture LOD bias from the backend (0 for spatial backends, S1-12); the temporal formula while
		// the jitter test stands in for a temporal backend. Only has an effect with mipmaps on (anisotropic >= 1).
		final float mipBias = jitterOn ? Jitter.temporalMipBias(renderWidth, displayWidth)
			: active != null && renderTargets.isScaled()
				? active.mipBias(new Size(renderWidth, renderHeight), new Size(displayWidth, displayHeight)) : 0f;
		if (textureArrayId != -1 && (mipBias != lastMipBias || textureArrayId != lastMipBiasTextureArray))
		{
			glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);
			glTexParameterf(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_LOD_BIAS, mipBias);
			lastMipBias = mipBias;
			lastMipBiasTextureArray = textureArrayId;
		}
		renderStats.mipBias = mipBias;

		glUniformMatrix4fv(uniEntityProj, false, IDENTITY);

		glUniform4i(uniEntityTint, 0, 0, 0, 0);

		// Bind uniforms
		glUniformBlockBinding(glProgram, uniBlockMain, 0);
		glUniform1i(uniTextures, 1); // texture sampler array is bound to texture1

		// Enable face culling
		glEnable(GL_CULL_FACE);

		// Enable blending
		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE);

		// Enable depth testing
		glDepthFunc(GL_GREATER);
		glEnable(GL_DEPTH_TEST);

		drawSkybox(scene, sky, cameraX, cameraY, cameraZ);

		checkGLErrors();
	}

	private void drawSkybox(Scene scene, int sky, float cameraX, float cameraY, float cameraZ)
	{
		Model skybox = scene.getSkybox();
		if (skybox == null)
		{
			sceneClearR = (sky >> 16 & 0xFF) / 255f; // S1-02: remembered for draw(), see field comment
			sceneClearG = (sky >> 8 & 0xFF) / 255f;
			sceneClearB = (sky & 0xFF) / 255f;
			glClearColor(sceneClearR, sceneClearG, sceneClearB, 1f);
			glClearDepth(0d);
			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
			return;
		}

		sceneClearR = sceneClearG = sceneClearB = 0f; // S1-02
		glClearColor(0f, 0f, 0f, 1f);
		glClearDepth(0d);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

		int size = skybox.getFaceCount() * 3 * VAO.VERT_SIZE;
		RenderThread rt = rts[0];
		VAO o = rt.vaoO.get(size);
		rt.modelUploader.uploadTempModel(skybox, 0, 0, 0, 0,
			(int) cameraX, (int) cameraY, (int) cameraZ, o.vbo.vb);

		float[] skyboxProjection = Mat4.translate(cameraX, cameraY, cameraZ);
		o.addRange(skyboxProjection, scene, Renderable.RENDERMODE_UNSORTED_NO_DEPTH);

		rt.vaoO.draw();

		glUniformMatrix4fv(uniEntityProj, false, IDENTITY);
	}

	@Override
	public void postSceneDraw(Scene scene)
	{
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			postDrawToplevel();
		}
		else
		{
			glUniform4i(uniEntityTint, 0, 0, 0, 0);
			glUniformMatrix4fv(uniEntityProj, false, IDENTITY);
		}
	}

	private void postDrawToplevel()
	{
		gpuTimers.end(GpuTimers.Stage.SCENE); // A-06
		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);
		glDisable(GL_DEPTH_TEST);

		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, awtContext.getFramebuffer(false));
		sceneFboValid = true;
	}

	private void blitSceneFbo()
	{
		int defaultFbo = awtContext.getFramebuffer(false);

		// S2-06: camera motion vectors from depth and this/last frame's unjittered matrices (A-05)
		frameInputs.motion = null;
		if (renderTargets.isTemporal() && renderTargets.motion() != null && temporalState.frameIndex() >= 0)
		{
			float[] currentToPrevious = Reprojection.currentToPrevious(temporalState.viewProj(), temporalState.prevViewProj());
			if (currentToPrevious != null)
			{
				gpuTimers.begin(GpuTimers.Stage.MOTION); // A-06
				if (cameraMotionPass.execute(renderTargets.depthTexture(), renderTargets.motion(), currentToPrevious, depthZeroToOne))
				{
					frameInputs.motion = renderTargets.motion();
				}
				gpuTimers.end(GpuTimers.Stage.MOTION);
			}
		}

		// Native size: blit straight from the scene target (resolving MSAA in the blit) with GL_NEAREST,
		// exactly like the stock plugin (G-30).
		int source = renderTargets.sceneFbo();
		int sourceWidth = renderTargets.renderWidth();
		int sourceHeight = renderTargets.renderHeight();
		int filter = GL_NEAREST;

		if (!renderTargets.isScaled() && renderTargets.needsResolve())
		{
			// S1-03: multisampled RGBA16F at native size; resolve first, then copy float to the 8-bit window
			source = renderTargets.resolveColor().fbo;
		}
		else if (renderTargets.isScaled())
		{
			// Stage 2, upscale pass (A-01, S1-05). S1-04: the color input is resolved to single-sample at
			// render resolution first, since a multisampled framebuffer can't be blitted to a different size.
			gpuTimers.begin(GpuTimers.Stage.UPSCALE); // A-06, G-31: includes the MSAA resolve
			frameInputs.color = renderTargets.resolveColor();
			frameInputs.sharpness = config.upscalerSharpness() / 100f; // S1-08, G-33: read live each frame
			Texture out = renderTargets.output();
			Upscaler upscaler = upscalerChain.active();
			boolean upscaled = false;
			if (upscaler != null)
			{
				try
				{
					upscaler.evaluate(frameInputs, out);
					upscaled = true;
				}
				catch (RuntimeException ex)
				{
					// G-13: never crash or go black; drop to the next backend and log why
					log.warn("Upscaler {} failed, falling back", upscaler.name(), ex);
					upscalerChain.markFailed(upscaler);
					selectUpscaler();
				}
			}
			gpuTimers.end(GpuTimers.Stage.UPSCALE);

			if (upscaled)
			{
				source = out.fbo;
				sourceWidth = out.width;
				sourceHeight = out.height;
			}
			else
			{
				// No working backend this frame: plain filtered stretch so the scene still shows (G-13)
				source = frameInputs.color.fbo;
				filter = GL_LINEAR;
			}
		}

		// Stage 5, present: into the 3D viewport rectangle on the window (S1-02, S1-11)
		gpuTimers.begin(GpuTimers.Stage.PRESENT); // A-06
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, defaultFbo);
		boolean debugViewShown = false;
		if (renderTargets.isTemporal() && config.upscalerDebugView() == DebugView.DEPTH)
		{
			// G-32, S2-02: show the depth buffer instead of the final image
			debugViewShown = debugViews.drawDepth(renderTargets.depthTexture(),
				displayX, displayY, displayWidth, displayHeight, depthZeroToOne,
				getDrawDistance() * Perspective.LOCAL_TILE_SIZE);
		}
		else if (config.upscalerDebugView() == DebugView.MOTION && frameInputs.motion != null)
		{
			// G-32, S2-06: show camera motion vectors (mid-grey = still); +/-16 px = full colour
			debugViewShown = debugViews.drawMotion(frameInputs.motion.id,
				displayX, displayY, displayWidth, displayHeight, 16f);
		}
		if (!debugViewShown)
		{
			glBindFramebuffer(GL_READ_FRAMEBUFFER, source);
			glBlitFramebuffer(0, 0, sourceWidth, sourceHeight,
				displayX, displayY, displayX + displayWidth, displayY + displayHeight,
				GL_COLOR_BUFFER_BIT, filter);
		}
		gpuTimers.end(GpuTimers.Stage.PRESENT);

		// Reset
		glBindFramebuffer(GL_READ_FRAMEBUFFER, defaultFbo);

		checkGLErrors();
	}

	/**
	 * S1-15: device-wide GPU memory in use (NVIDIA only, via GL_NVX_gpu_memory_info), for spotting leaks on the
	 * debug overlay. Includes other processes, so watch the trend, not the absolute value.
	 */
	private void updateGpuMemory()
	{
		if (!config.upscalerDebugOverlay() || !glCapabilities.GL_NVX_gpu_memory_info)
		{
			renderStats.gpuMemUsedMb = -1;
			return;
		}
		if (gpuMemoryFrame++ % 60 != 0)
		{
			return;
		}
		int totalKb = glGetInteger(GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX);
		int freeKb = glGetInteger(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX);
		renderStats.gpuMemTotalMb = totalKb / 1024;
		renderStats.gpuMemUsedMb = (totalKb - freeKb) / 1024;
	}

	/**
	 * A-04: pick the backend (best supported, skipping failed ones) and size it to the current targets.
	 */
	private void selectUpscaler()
	{
		// S1-13: start at the configured backend (Off has none; the scale is forced to 100% instead)
		String requested = config.upscalerBackend().upscalerName();
		Upscaler previous = upscalerChain.active();
		Upscaler upscaler = upscalerChain.select(requested == null ? "" : requested, gpuCaps);
		String reason = upscalerChain.fallbackReason();
		if (previous != null && upscaler != previous)
		{
			historyReset.request(HistoryReset.Reason.BACKEND_SWITCH); // S2-08
		}
		if (upscaler == null)
		{
			log.warn("No upscaler backend available: {}", reason);
			return;
		}

		log.debug("Upscaler backend: {}{}", upscaler.name(), reason.isEmpty() ? "" : " (fallback: " + reason + ")");
		if (renderTargets.sceneFbo() != 0)
		{
			upscaler.resize(new Size(renderTargets.renderWidth(), renderTargets.renderHeight()),
				new Size(renderTargets.displayWidth(), renderTargets.displayHeight()));
			// resize may bind its own framebuffers; draw() expects the default one bound between frames
			glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		}
	}

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized)
		{
			return;
		}

		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
		z.renderOpaque(zx - offset, zz - offset, ctx.minLevel, ctx.level, ctx.maxLevel, ctx.hideRoofIds);

		checkGLErrors();
	}

	private static final int ALPHA_ZSORT_CLOSE = 2048;

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized)
		{
			return;
		}

		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
		int dx = ctx.cameraX - ((zx - offset) << 10);
		int dz = ctx.cameraZ - ((zz - offset) << 10);
		boolean close = dx * dx + dz * dz < ALPHA_ZSORT_CLOSE * ALPHA_ZSORT_CLOSE;

		if (level == 0)
		{
			z.alphaSort(zx - offset, zz - offset, ctx.cameraX, ctx.cameraY, ctx.cameraZ);
			z.multizoneLocs(scene, zx - offset, zz - offset, ctx.cameraX, ctx.cameraZ, ctx.zones);
		}

		RenderThread rt = rts[0];
		z.renderAlpha(rt.modelUploader, zx - offset, zz - offset, cameraYaw, cameraPitch, ctx.minLevel, ctx.level, ctx.maxLevel, level, ctx.hideRoofIds, !close || (scene.getOverrideAmount() > 0));

		checkGLErrors();
	}

	@Override
	public void drawPass(Projection projection, Scene scene, int pass)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		if (pass == DrawCallbacks.PASS_OPAQUE)
		{
			if (scene.getWorldViewId() == WorldView.TOPLEVEL)
			{
				for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
				{
					rts[i].vaoO.draw();
				}
			}
			else
			{
				glUniformMatrix4fv(uniEntityProj, false, IDENTITY);
			}
		}
		else if (pass == DrawCallbacks.PASS_ALPHA)
		{
			for (int x = 0; x < ctx.sizeX; ++x)
			{
				for (int z = 0; z < ctx.sizeZ; ++z)
				{
					Zone zone = ctx.zones[x][z];
					zone.removeTemp();
				}
			}
		}
		else if (pass == DrawCallbacks.PRE_PASS_ALPHA)
		{
			for (int i = 0; i < rts.length; ++i)
			{
				rts[i].vaoA.unmap();
			}

			glUniformMatrix4fv(uniEntityProj, false, ctx.projection);
			glUniform4i(uniEntityTint, scene.getOverrideHue(), scene.getOverrideSaturation(), scene.getOverrideLuminance(), scene.getOverrideAmount());
		}

		checkGLErrors();
	}

	@Override
	public void drawDynamic(int renderThreadId, Projection worldProjection, Scene scene, TileObject tileObject, Renderable r, Model m, int orient, int x, int y, int z)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		if (!renderCallbackManager.drawObject(scene, tileObject))
		{
			return;
		}

		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		if (m.getFaceTransparencies() == null)
		{
			RenderThread rt = rts[renderThreadId + 1];
			VAO o = rt.vaoO.get(size);
			if (o == null)
			{
				return;
			}

			rt.modelUploader.uploadTempModel(m, orient, x, y, z,
				ctx.cameraX, ctx.cameraY, ctx.cameraZ, o.vbo.vb);
			o.addRange(ctx.projection, scene, 0);
		}
		else
		{
			m.calculateBoundsCylinder();

			RenderThread rt = rts[renderThreadId + 1];
			VAO o = rt.vaoO.get(size);
			VAO a = rt.vaoA.get(size);
			if (o == null || a == null)
			{
				return;
			}

			ModelUploader sorter = rt.modelUploader;

			int start = a.vbo.vb.position();
			try
			{
				sorter.uploadSortedModel(rt, worldProjection, m, orient, x, y, z,
					ctx.cameraX, ctx.cameraY, ctx.cameraZ, o.vbo.vb, a.vbo.vb, false);
			}
			catch (Exception ex)
			{
				log.debug("error drawing entity", ex);
			}
			int end = a.vbo.vb.position();

			o.addRange(ctx.projection, scene, 0);

			if (end > start)
			{
				int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? SCENE_OFFSET : 0;
				int zx = (x >> 10) + (offset >> 3);
				int zz = (z >> 10) + (offset >> 3);
				Zone zone = ctx.zones[zx][zz];

				// level is checked prior to this callback being run, in order to cull clickboxes, but
				// tileObject.getPlane()>maxLevel if visbelow is set - lower the object to the max level
				int plane = Math.min(ctx.maxLevel, tileObject.getPlane());
				// renderable modelheight is typically not set here because DynamicObject doesn't compute it on the returned model
				zone.addTempAlphaModel(a.vao, start, end, plane, x & 1023, y, z & 1023);
			}
		}
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orient, int x, int y, int z)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		if (!renderCallbackManager.drawObject(scene, gameObject))
		{
			return;
		}

		Renderable renderable = gameObject.getRenderable();
		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		int renderMode = renderable.getRenderMode();
		if (renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH || m.getFaceTransparencies() != null || m.getTransparency() != 0)
		{
			RenderThread rt = rts[0];
			VAO o = rt.vaoO.get(size);
			VAO a = rt.vaoA.get(size);
			ModelUploader uploader = rt.modelUploader;

			int start = a.vbo.vb.position();
			m.calculateBoundsCylinder();
			try
			{
				uploader.uploadSortedModel(rt, worldProjection, m, orient, x, y, z,
					ctx.cameraX, ctx.cameraY, ctx.cameraZ, o.vbo.vb, a.vbo.vb,
					renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH);
			}
			catch (Exception ex)
			{
				log.debug("error drawing entity", ex);
			}
			int end = a.vbo.vb.position();

			o.addRange(ctx.projection, scene, renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH ? renderMode : 0);

			if (end > start)
			{
				int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
				int zx = (gameObject.getX() >> 10) + offset;
				int zz = (gameObject.getY() >> 10) + offset;
				Zone zone = ctx.zones[zx][zz];
				int plane = Math.min(ctx.maxLevel, gameObject.getPlane());
				zone.addTempAlphaModel(a.vao, start, end, plane, x & 1023, y - renderable.getModelHeight() /* to render players over locs */, z & 1023);
			}
		}
		else
		{
			RenderThread rt = rts[0];
			VAO o = rt.vaoO.get(size);
			ModelUploader uploader = rt.modelUploader;
			uploader.uploadTempModel(m, orient, x, y, z,
				ctx.cameraX, ctx.cameraY, ctx.cameraZ, o.vbo.vb);
			o.addRange(ctx.projection, scene, 0);
		}
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zz)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		Zone z = ctx.zones[zx][zz];
		if (!z.invalidate)
		{
			z.invalidate = true;
			log.debug("Zone invalidated: wx={} x={} z={}", scene.getWorldViewId(), zx, zz);
		}
	}

	@Subscribe
	public void onPostClientTick(PostClientTick event)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}

		rebuild(wv);
		for (WorldEntity we : wv.worldEntities())
		{
			wv = we.getWorldView();
			rebuild(wv);
		}
	}

	private void rebuild(WorldView wv)
	{
		SceneContext ctx = context(wv);
		if (ctx == null)
		{
			return;
		}

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];
				if (!zone.invalidate)
				{
					continue;
				}

				assert zone.initialized;
				zone.free();
				zone = ctx.zones[x][z] = new Zone();

				Scene scene = wv.getScene();
				clientUploader.zoneSize(scene, zone, x, z);

				VBO o = null, a = null;
				int sz = zone.sizeO * Zone.VERT_SIZE * 3;
				if (sz > 0)
				{
					o = new VBO(sz);
					o.init(GL_STATIC_DRAW);
					o.map();
				}

				sz = zone.sizeA * Zone.VERT_SIZE * 3;
				if (sz > 0)
				{
					a = new VBO(sz);
					a.init(GL_STATIC_DRAW);
					a.map();
				}

				zone.init(o, a);

				clientUploader.uploadZone(scene, zone, x, z);

				zone.unmap();
				zone.initialized = true;
				zone.dirty = true;

				log.debug("Rebuilt zone wv={} x={} z={}", wv.getId(), x, z);
			}
		}
	}

	private void prepareInterfaceTexture(int canvasWidth, int canvasHeight)
	{
		if (canvasWidth != lastCanvasWidth || canvasHeight != lastCanvasHeight)
		{
			lastCanvasWidth = canvasWidth;
			lastCanvasHeight = canvasHeight;

			glBindTexture(GL_TEXTURE_2D, interfaceTexture);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, canvasWidth, canvasHeight, 0, GL_BGRA, GL_UNSIGNED_BYTE, 0);
			glBindTexture(GL_TEXTURE_2D, 0);
		}

		final BufferProvider bufferProvider = client.getBufferProvider();
		final int[] pixels = bufferProvider.getPixels();
		final int width = bufferProvider.getWidth();
		final int height = bufferProvider.getHeight();

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePbo);
		glBufferData(GL_PIXEL_UNPACK_BUFFER, (long) width * height * Integer.BYTES, GL_STREAM_DRAW);
		ByteBuffer interfaceBuf = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY);
		if (interfaceBuf != null)
		{
			interfaceBuf
				.asIntBuffer()
				.put(pixels, 0, width * height);
			glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
		}
		else
		{
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
			return;
		}
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	@Override
	public void draw(int overlayColor)
	{
		final GameState gameState = client.getGameState();
		if (gameState == GameState.STARTING)
		{
			return;
		}

		final TextureProvider textureProvider = client.getTextureProvider();
		if (textureArrayId == -1 && textureProvider != null)
		{
			// lazy init textures as they may not be loaded at plugin start.
			// this will return -1 and retry if not all textures are loaded yet, too.
			textureArrayId = textureManager.initTextureArray(textureProvider);
			if (textureArrayId > -1)
			{
				// if texture upload is successful, compute and set texture animations
				float[] texAnims = textureManager.computeTextureAnimations(textureProvider);
				glUseProgram(glProgram);
				glUniform2fv(uniTextureAnimations, texAnims);
				glUseProgram(0);
			}
		}

		final int canvasHeight = client.getCanvasHeight();
		final int canvasWidth = client.getCanvasWidth();

		prepareInterfaceTexture(canvasWidth, canvasHeight);

		if (sceneFboValid)
		{
			// S1-02: the scene target now only covers the 3D viewport; fill the rest of the window with the
			// scene's clear color, as the stock full-canvas blit did (G-30)
			glClearColor(sceneClearR, sceneClearG, sceneClearB, 1);
			glClear(GL_COLOR_BUFFER_BIT);
			blitSceneFbo();
		}
		else
		{
			glClearColor(0, 0, 0, 1);
			glClear(GL_COLOR_BUFFER_BIT);
		}

		// Texture on UI
		gpuTimers.begin(GpuTimers.Stage.UI); // A-06: stage 4, UI composite
		drawUi(overlayColor, canvasHeight, canvasWidth);
		gpuTimers.end(GpuTimers.Stage.UI);
		gpuTimers.endFrame();
		gpuTimers.setEnabled(config.upscalerDebugOverlay()); // G-31: only time while the overlay is shown
		updateGpuMemory();

		try
		{
			awtContext.swapBuffers();
		}
		catch (RuntimeException ex)
		{
			// this is always fatal
			if (!canvas.isValid())
			{
				// this might be AWT shutting down on VM shutdown, ignore it
				return;
			}

			log.error("error swapping buffers", ex);

			// try to stop the plugin
			SwingUtilities.invokeLater(() ->
			{
				try
				{
					pluginManager.stopPlugin(this);
				}
				catch (PluginInstantiationException ex2)
				{
					log.error("error stopping plugin", ex2);
				}
			});
			return;
		}

		drawManager.processDrawComplete(this::screenshot);

		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));

		checkGLErrors();
	}

	private void drawUi(final int overlayColor, final int canvasHeight, final int canvasWidth)
	{
		glEnable(GL_BLEND);
		glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);

		// Use the texture bound in the first pass
		final UIScalingMode uiScalingMode = config.uiScalingMode();
		glUseProgram(glUiProgram);
		glUniform1i(uniTex, 0);
		glUniform2i(uniTexSourceDimensions, canvasWidth, canvasHeight);
		glUniform4f(uniUiAlphaOverlay,
			(overlayColor >> 16 & 0xFF) / 255f,
			(overlayColor >> 8 & 0xFF) / 255f,
			(overlayColor & 0xFF) / 255f,
			(overlayColor >>> 24) / 255f
		);
		glUniform1f(uniUiColorblindIntensity, config.colorBlindIntensity());

		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			glDpiAwareViewport(0, 0, dim.width, dim.height);
			glUniform2i(uniTexTargetDimensions, getScaledValue(t.getScaleX(), dim.width), getScaledValue(t.getScaleY(), dim.height));
		}
		else
		{
			glDpiAwareViewport(0, 0, canvasWidth, canvasHeight);
			glUniform2i(uniTexTargetDimensions, getScaledValue(t.getScaleX(), canvasWidth), getScaledValue(t.getScaleY(), canvasHeight));
		}

		// Set the sampling function used when stretching the UI.
		// This is probably better done with sampler objects instead of texture parameters, but this is easier and likely more portable.
		// See https://www.khronos.org/opengl/wiki/Sampler_Object for details.
		// GL_NEAREST makes sampling for bicubic/xBR simpler, so it should be used whenever linear/hybrid isn't
		final int function = uiScalingMode == UIScalingMode.LINEAR || uiScalingMode == UIScalingMode.HYBRID ? GL_LINEAR : GL_NEAREST;
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, function);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, function);

		// Texture on UI
		glBindVertexArray(vaoUiHandle);
		glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

		// Reset
		glBindTexture(GL_TEXTURE_2D, 0);
		glBindVertexArray(0);
		glUseProgram(0);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glDisable(GL_BLEND);
	}

	/**
	 * Convert the front framebuffer to an Image
	 *
	 * @return
	 */
	private Image screenshot()
	{
		int width = client.getCanvasWidth();
		int height = client.getCanvasHeight();

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			width = dim.width;
			height = dim.height;
		}

		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		width = getScaledValue(t.getScaleX(), width);
		height = getScaledValue(t.getScaleY(), height);

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

		glReadBuffer(awtContext.getBufferMode());
		glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixels);

		// glReadPixels returns rows bottom-up, flip them to top-down
		int[] row = new int[width];
		for (int y0 = 0, y1 = height - 1; y0 < y1; ++y0, --y1)
		{
			System.arraycopy(pixels, y0 * width, row, 0, width);
			System.arraycopy(pixels, y1 * width, pixels, y0 * width, width);
			System.arraycopy(row, 0, pixels, y1 * width, width);
		}

		return image;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		GameState state = gameStateChanged.getGameState();
		if (state.getState() < GameState.LOADING.getState())
		{
			// this is to avoid scene fbo blit when going from <loading to >=loading,
			// but keep it when doing >loading to loading
			sceneFboValid = false;
		}
		if (state == GameState.STARTING)
		{
			if (textureArrayId != -1)
			{
				textureManager.freeTextureArray(textureArrayId);
			}
			textureArrayId = -1;
			lastAnisotropicFilteringLevel = -1;
			lastMipBiasTextureArray = -1; // S2-09: reapply the bias to the new texture array
		}

		// S2-08: login and logout are cuts for temporal history
		if (state == GameState.LOGGED_IN)
		{
			historyReset.request(HistoryReset.Reason.LOGIN);
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			historyReset.request(HistoryReset.Reason.LOGOUT);
			historyReset.forgetCamera();
		}
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene)
	{
		historyReset.request(HistoryReset.Reason.SCENE_LOAD); // S2-08: region or scene reload
		if (scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			loadSubScene(worldView, scene);
			return;
		}

		if (nextZones != null)
		{
			log.debug("Double zone load!");
			// The previous scene load just gets dropped, this is uncommon and requires a back to back map build packet
			// while having the first load take more than a full server cycle to complete
			CountDownLatch latch = new CountDownLatch(1);
			clientThread.invoke(() ->
			{
				for (int x = 0; x < NUM_ZONES; ++x)
				{
					for (int z = 0; z < NUM_ZONES; ++z)
					{
						Zone zone = nextZones[x][z];
						assert !zone.cull;
						// anything initialized is a reused zone and so shouldn't be freed
						if (!zone.initialized)
						{
							zone.unmap();
							zone.initialized = true;
							zone.free();
						}
					}
				}
				latch.countDown();
			});
			try
			{
				latch.await();
			}
			catch (InterruptedException e)
			{
				throw new RuntimeException(e);
			}
			nextZones = null;
			nextRoofChanges = null;
		}

		SceneContext ctx = root;
		Scene prev = client.getTopLevelWorldView().getScene();

		regionManager.prepare(scene);

		int dx = scene.getBaseX() - prev.getBaseX() >> 3;
		int dy = scene.getBaseY() - prev.getBaseY() >> 3;

		final int SCENE_ZONES = NUM_ZONES;

		// initially mark every zone as needing culled
		for (int x = 0; x < SCENE_ZONES; ++x)
		{
			for (int z = 0; z < SCENE_ZONES; ++z)
			{
				ctx.zones[x][z].cull = true;
			}
		}

		Map<Integer, Integer> roofChanges = new HashMap<>();

		// find zones which overlap and copy them
		Zone[][] newZones = new Zone[SCENE_ZONES][SCENE_ZONES];
		final GameState gameState = client.getGameState();
		if (prev.isInstance() == scene.isInstance()
			&& gameState == GameState.LOGGED_IN)
		{
			int[][][] prevTemplates = prev.getInstanceTemplateChunks();
			int[][][] curTemplates = scene.getInstanceTemplateChunks();

			int[][][] prids = prev.getRoofs();
			int[][][] nrids = scene.getRoofs();

			for (int x = 0; x < SCENE_ZONES; ++x)
			{
				next:
				for (int z = 0; z < SCENE_ZONES; ++z)
				{
					int ox = x + dx;
					int oz = z + dy;

					// Reused the old zone if it is also in the new scene, except for the edges, to work around
					// tile blending, (edge) shadows, sharelight, etc.
					if (canReuse(ctx.zones, ox, oz))
					{
						if (scene.isInstance())
						{
							// Convert from modified chunk coordinates to Jagex chunk coordinates
							int jx = x - (SCENE_OFFSET / 8);
							int jz = z - (SCENE_OFFSET / 8);
							int jox = ox - (SCENE_OFFSET / 8);
							int joz = oz - (SCENE_OFFSET / 8);
							// Check Jagex chunk coordinates are within the Jagex scene
							if (jx >= 0 && jx < Constants.SCENE_SIZE / 8 && jz >= 0 && jz < Constants.SCENE_SIZE / 8)
							{
								if (jox >= 0 && jox < Constants.SCENE_SIZE / 8 && joz >= 0 && joz < Constants.SCENE_SIZE / 8)
								{
									for (int level = 0; level < 4; ++level)
									{
										int prevTemplate = prevTemplates[level][jox][joz];
										int curTemplate = curTemplates[level][jx][jz];
										if (prevTemplate != curTemplate)
										{
											log.error("Instance template reuse mismatch! prev={} cur={}", prevTemplate, curTemplate);
											continue next;
										}
									}
								}
							}
						}

						Zone old = ctx.zones[ox][oz];
						assert old.initialized;

						if (old.dirty)
						{
							continue;
						}

						assert old.sizeO > 0 || old.sizeA > 0;

						// Roof ids aren't consistent between scenes, so build a mapping of old -> new roof ids
						// Sometimes groups split or merge, so we can't copy the zone in that case
						for (int level = 0; level < 4; level++)
						{
							for (int tx = 0; tx < 8; tx++)
							{
								for (int tz = 0; tz < 8; tz++)
								{
									int prid = prids[level][(ox << 3) + tx][(oz << 3) + tz];
									int nrid = nrids[level][(x << 3) + tx][(z << 3) + tz];

									if (prid != nrid && (prid == 0 || nrid == 0))
									{
										log.trace("Roof mismatch: {} -> {}", prid, nrid);
										continue next;
									}

									Integer orid = roofChanges.putIfAbsent(prid, nrid);
									if (orid == null)
									{
										log.trace("Roof change: {} -> {}", prid, nrid);
									}
									else if (orid != nrid)
									{
										log.trace("Roof mismatch: {} -> {} vs {}", prid, nrid, orid);
										continue next;
									}
								}
							}
						}

						assert old.cull;
						old.cull = false;

						newZones[x][z] = old;
					}
				}
			}
		}

		// Fill out any zones that weren't copied
		for (int x = 0; x < SCENE_ZONES; ++x)
		{
			for (int z = 0; z < SCENE_ZONES; ++z)
			{
				if (newZones[x][z] == null)
				{
					newZones[x][z] = new Zone();
				}
			}
		}

		// size the zones which require upload
		Stopwatch sw = Stopwatch.createStarted();
		int len = 0, lena = 0;
		int reused = 0, newzones = 0;
		for (int x = 0; x < NUM_ZONES; ++x)
		{
			for (int z = 0; z < NUM_ZONES; ++z)
			{
				Zone zone = newZones[x][z];
				if (!zone.initialized)
				{
					assert zone.glVao == 0;
					assert zone.glVaoA == 0;
					mapUploader.zoneSize(scene, zone, x, z);
					len += zone.sizeO;
					lena += zone.sizeA;
					newzones++;
				}
				else
				{
					reused++;
				}
			}
		}
		log.debug("Scene size time {} reused {} new {} len opaque {} size opaque {}kb len alpha {} size alpha {}kb",
			sw, reused, newzones,
			len, (len * Zone.VERT_SIZE * 3) / 1024,
			lena, (lena * Zone.VERT_SIZE * 3) / 1024);

		// allocate buffers for zones which require upload
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invoke(() ->
		{
			for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE >> 3; ++x)
			{
				for (int z = 0; z < Constants.EXTENDED_SCENE_SIZE >> 3; ++z)
				{
					Zone zone = newZones[x][z];

					if (zone.initialized)
					{
						continue;
					}

					VBO o = null, a = null;
					int sz = zone.sizeO * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						o = new VBO(sz);
						o.init(GL_STATIC_DRAW);
						o.map();
					}

					sz = zone.sizeA * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						a = new VBO(sz);
						a.init(GL_STATIC_DRAW);
						a.map();
					}

					zone.init(o, a);
				}
			}

			latch.countDown();
		});
		try
		{
			latch.await();
		}
		catch (InterruptedException e)
		{
			throw new RuntimeException(e);
		}

		// upload zones
		sw = Stopwatch.createStarted();
		for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE >> 3; ++x)
		{
			for (int z = 0; z < Constants.EXTENDED_SCENE_SIZE >> 3; ++z)
			{
				Zone zone = newZones[x][z];

				if (!zone.initialized)
				{
					mapUploader.uploadZone(scene, zone, x, z);
				}
			}
		}
		log.debug("Scene upload time {}", sw);

		nextZones = newZones;
		nextRoofChanges = roofChanges;
	}

	private static boolean canReuse(Zone[][] zones, int zx, int zz)
	{
		// For tile blending, sharelight, and shadows to work correctly, the zones surrounding
		// the zone must be valid.
		for (int x = zx - 1; x <= zx + 1; ++x)
		{
			if (x < 0 || x >= NUM_ZONES)
			{
				return false;
			}
			for (int z = zz - 1; z <= zz + 1; ++z)
			{
				if (z < 0 || z >= NUM_ZONES)
				{
					return false;
				}
				Zone zone = zones[x][z];
				if (!zone.initialized)
				{
					return false;
				}
				if (zone.sizeO == 0 && zone.sizeA == 0)
				{
					return false;
				}
			}
		}
		return true;
	}

	private void loadSubScene(WorldView worldView, Scene scene)
	{
		int worldViewId = scene.getWorldViewId();
		assert worldViewId != -1;

		log.debug("Loading world view {}", worldViewId);

		SceneContext ctx0 = subs[worldViewId];
		if (ctx0 != null)
		{
			log.info("Reload of an already loaded worldview?");
			return;
		}

		final SceneContext ctx = new SceneContext(worldView.getSizeX() >> 3, worldView.getSizeY() >> 3);
		subs[worldViewId] = ctx;

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];
				mapUploader.zoneSize(scene, zone, x, z);
			}
		}

		// allocate buffers for zones which require upload
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invoke(() ->
		{
			for (int x = 0; x < ctx.sizeX; ++x)
			{
				for (int z = 0; z < ctx.sizeZ; ++z)
				{
					Zone zone = ctx.zones[x][z];

					VBO o = null, a = null;
					int sz = zone.sizeO * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						o = new VBO(sz);
						o.init(GL_STATIC_DRAW);
						o.map();
					}

					sz = zone.sizeA * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						a = new VBO(sz);
						a.init(GL_STATIC_DRAW);
						a.map();
					}

					zone.init(o, a);
				}
			}

			latch.countDown();
		});
		try
		{
			latch.await();
		}
		catch (InterruptedException e)
		{
			throw new RuntimeException(e);
		}

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];

				mapUploader.uploadZone(scene, zone, x, z);
			}
		}
	}

	@Override
	public void despawnWorldView(WorldView worldView)
	{
		int worldViewId = worldView.getId();
		if (worldViewId != WorldView.TOPLEVEL)
		{
			log.debug("WorldView despawn: {}", worldViewId);
			var sub = subs[worldViewId];
			if (sub == null)
			{
				return;
			}

			sub.free();
			subs[worldViewId] = null;
		}
	}

	@Override
	public void swapScene(Scene scene)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			swapSub(scene);
			return;
		}

		SceneContext ctx = root;
		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];

				if (zone.cull)
				{
					zone.free();
				}
				else
				{
					// reused zone
					zone.updateRoofs(nextRoofChanges);
				}
			}
		}
		nextRoofChanges = null;

		ctx.zones = nextZones;
		nextZones = null;

		// setup vaos
		for (int x = 0; x < ctx.zones.length; ++x) // NOPMD: ForLoopCanBeForeach
		{
			for (int z = 0; z < ctx.zones[0].length; ++z)
			{
				Zone zone = ctx.zones[x][z];

				if (!zone.initialized)
				{
					zone.unmap();
					zone.initialized = true;
				}
			}
		}

		checkGLErrors();
	}

	private void swapSub(Scene scene)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		// setup vaos
		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];

				if (!zone.initialized)
				{
					zone.unmap();
					zone.initialized = true;
				}
			}
		}
		log.debug("WorldView ready: {}", scene.getWorldViewId());
	}

	private int getScaledValue(final double scale, final int value)
	{
		return (int) (value * scale);
	}

	private void glDpiAwareViewport(final int x, final int y, final int width, final int height)
	{
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		glViewport(
			getScaledValue(t.getScaleX(), x),
			getScaledValue(t.getScaleY(), y),
			getScaledValue(t.getScaleX(), width),
			getScaledValue(t.getScaleY(), height));
	}

	private int getDrawDistance()
	{
		return Ints.constrainToRange(config.drawDistance(), 0, MAX_DISTANCE);
	}

	private void checkGLErrors()
	{
		if (!log.isDebugEnabled())
		{
			return;
		}

		for (; ; )
		{
			int err = glGetError();
			if (err == GL_NO_ERROR)
			{
				return;
			}

			String errStr;
			switch (err)
			{
				case GL_INVALID_ENUM:
					errStr = "INVALID_ENUM";
					break;
				case GL_INVALID_VALUE:
					errStr = "INVALID_VALUE";
					break;
				case GL_INVALID_OPERATION:
					errStr = "INVALID_OPERATION";
					break;
				case GL_INVALID_FRAMEBUFFER_OPERATION:
					errStr = "INVALID_FRAMEBUFFER_OPERATION";
					break;
				default:
					errStr = "" + err;
					break;
			}

			log.debug("glGetError:", new Exception(errStr));
		}
	}

	@Subscribe
	private void onCommandExecuted(CommandExecuted event)
	{
		if (event.getCommand().equals("upscaler"))
		{
			// S2-05: debug dump of the temporal state (developer mode command ::upscaler)
			clientThread.invokeLater(() ->
			{
				log.info("Upscaler: temporal inputs {}, frame {}, frame time {} ms, has previous {}",
					config.temporalInputs() ? "on" : "off", temporalState.frameIndex(),
					String.format("%.2f", temporalState.frameTimeMs()), temporalState.hasPrevious());
				log.info("Upscaler: depth = {} (reversed-Z, infinite far, near z = 100)",
					depthZeroToOne ? "100 / z_view" : "0.5 + 50 / z_view");
				log.info("Upscaler: viewProj (unjittered)\n{}", TemporalState.format(temporalState.viewProj()));
				log.info("Upscaler: prevViewProj (unjittered)\n{}", TemporalState.format(temporalState.prevViewProj()));
			});
		}
		else if (event.getCommand().equals("gpumem"))
		{
			int totalSzKb = 0;
			for (int i = 0; i < rts.length; ++i)
			{
				RenderThread rt = rts[i];
				int szKb = rt.vaoO.size() + rt.vaoA.size();
				totalSzKb += szKb;
				log.info("RenderThread{}: {}kb", i, szKb);
			}
			log.info("Total: {}kb", totalSzKb);
		}
	}
}
