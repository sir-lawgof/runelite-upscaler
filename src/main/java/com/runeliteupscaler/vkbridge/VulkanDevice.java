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
package com.runeliteupscaler.vkbridge;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRExternalMemoryFd;
import org.lwjgl.vulkan.KHRExternalMemoryWin32;
import org.lwjgl.vulkan.KHRExternalSemaphoreFd;
import org.lwjgl.vulkan.KHRExternalSemaphoreWin32;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceIDProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

/**
 * S2-20, S2-21, S2-26: the Vulkan side of the GL-Vulkan bridge. Creates a Vulkan 1.1 instance and a logical
 * device on the same physical GPU as the OpenGL context (matched by device UUID), with the external memory and
 * semaphore extensions the bridge needs. Validation layers are enabled when installed (G-06) and report through
 * the plugin log. Everything is destroyed in {@link #close()}, where validation reports any leaked objects.
 * Construct with {@link #create}; missing support gives an unsupported result instead of an exception (G-13).
 */
@Slf4j
public final class VulkanDevice implements AutoCloseable
{
	private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

	/**
	 * LWJGL system property for the per-thread MemoryStack size in KB (default 64). It is read once when LWJGL's
	 * MemoryStack class loads, so it must be set before RuneLite starts; the developer launcher does that.
	 * Needed because LWJGL's VkInstance constructor lists all device extensions on that stack (8.4, Q-08).
	 */
	public static final String STACK_SIZE_PROPERTY = "org.lwjgl.system.stackSize";

	/** Result of {@link #create}: a device, or the reason there is none. */
	public static final class Result
	{
		public final VulkanDevice device;
		public final String reason;

		Result(VulkanDevice device, String reason)
		{
			this.device = device;
			this.reason = reason;
		}
	}

	private VkInstance instance;
	private long messenger;
	private VkDebugUtilsMessengerCallbackEXT callback;
	private VkDevice device;
	private VkQueue queue;
	private int queueFamily;
	private final AtomicInteger validationMessages = new AtomicInteger();

	public String deviceName = "";
	public String deviceUuid = "";
	public String apiVersion = "";
	public boolean validationEnabled;
	public List<String> enabledDeviceExtensions = new ArrayList<>();

	private VulkanDevice()
	{
	}

	/**
	 * @param glDeviceUuidHex device UUID reported by GL (InteropSupport), lowercase hex
	 * @param platform        decides the Win32 or FD flavour of the external memory/semaphore extensions
	 * @param wantValidation  enable VK_LAYER_KHRONOS_validation if it is installed (G-06)
	 */
	public static Result create(String glDeviceUuidHex, InteropSupport.Platform platform, boolean wantValidation)
	{
		VulkanDevice vd = new VulkanDevice();
		try
		{
			String reason = vd.init(glDeviceUuidHex, platform, wantValidation);
			if (reason != null)
			{
				vd.close();
				return new Result(null, reason);
			}
			return new Result(vd, "");
		}
		catch (Throwable ex)
		{
			// UnsatisfiedLinkError or IllegalStateException when the Vulkan loader is missing, and so on
			log.warn("Vulkan device creation failed", ex);
			vd.close();
			if (ex instanceof OutOfMemoryError && String.valueOf(ex.getMessage()).contains("stack space"))
			{
				// LWJGL's VkInstance constructor lists every device extension on the 64 KB default MemoryStack (8.4)
				return new Result(null, "LWJGL stack too small: start the JVM with -D" + STACK_SIZE_PROPERTY + "=1024");
			}
			return new Result(null, "Vulkan error: " + ex);
		}
	}

	private String init(String glUuid, InteropSupport.Platform platform, boolean wantValidation)
	{
		if (platform != InteropSupport.Platform.WINDOWS && platform != InteropSupport.Platform.LINUX)
		{
			return "no GL-Vulkan interop on " + platform + " (S2-28)";
		}
		if (glUuid == null || glUuid.isEmpty())
		{
			return "no GL device UUID to match (S2-20)";
		}

		ensureLoader();

		try (MemoryStack stack = MemoryStack.stackPush())
		{
			// Instance: Vulkan 1.1 for vkGetPhysicalDeviceProperties2 and core external memory/semaphores
			validationEnabled = wantValidation && hasInstanceLayer(stack, VALIDATION_LAYER);
			VkApplicationInfo app = VkApplicationInfo.calloc(stack)
				.sType$Default()
				.pApplicationName(stack.UTF8("runelite-upscaler"))
				.pEngineName(stack.UTF8("runelite-upscaler"))
				.apiVersion(VK_API_VERSION_1_1);
			VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(stack)
				.sType$Default()
				.pApplicationInfo(app);
			if (validationEnabled)
			{
				ici.ppEnabledLayerNames(stack.pointers(stack.UTF8(VALIDATION_LAYER)));
				ici.ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)));
			}
			PointerBuffer pp = stack.mallocPointer(1);
			int err = vkCreateInstance(ici, null, pp);
			if (err != VK_SUCCESS)
			{
				return "vkCreateInstance failed: " + err;
			}
			instance = new VkInstance(pp.get(0), ici);

			if (validationEnabled)
			{
				callback = VkDebugUtilsMessengerCallbackEXT.create((severity, types, data, user) ->
				{
					VkDebugUtilsMessengerCallbackDataEXT d = VkDebugUtilsMessengerCallbackDataEXT.create(data);
					validationMessages.incrementAndGet();
					if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0)
					{
						log.warn("Vulkan validation error: {}", d.pMessageString());
					}
					else
					{
						log.warn("Vulkan validation: {}", d.pMessageString());
					}
					return VK_FALSE;
				});
				VkDebugUtilsMessengerCreateInfoEXT mci = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
					.sType$Default()
					.messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
					.messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
						| VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
					.pfnUserCallback(callback);
				LongBuffer pm = stack.mallocLong(1);
				if (vkCreateDebugUtilsMessengerEXT(instance, mci, null, pm) == VK_SUCCESS)
				{
					messenger = pm.get(0);
				}
			}

			// S2-20: physical device with the same UUID as the GL context
			IntBuffer count = stack.mallocInt(1);
			vkEnumeratePhysicalDevices(instance, count, null);
			if (count.get(0) == 0)
			{
				return "no Vulkan devices";
			}
			PointerBuffer devices = stack.mallocPointer(count.get(0));
			vkEnumeratePhysicalDevices(instance, count, devices);
			VkPhysicalDevice physical = null;
			List<String> seen = new ArrayList<>();
			for (int i = 0; i < devices.capacity(); i++)
			{
				VkPhysicalDevice pd = new VkPhysicalDevice(devices.get(i), instance);
				VkPhysicalDeviceIDProperties id = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
				VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(id);
				vkGetPhysicalDeviceProperties2(pd, props);
				String uuid = InteropSupport.hex(id.deviceUUID());
				String name = props.properties().deviceNameString();
				seen.add(name + " " + uuid);
				if (uuid.equals(glUuid))
				{
					physical = pd;
					deviceName = name;
					deviceUuid = uuid;
					int v = props.properties().apiVersion();
					apiVersion = VK_API_VERSION_MAJOR(v) + "." + VK_API_VERSION_MINOR(v) + "." + VK_API_VERSION_PATCH(v);
				}
			}
			log.info("Vulkan devices: {}", seen);
			if (physical == null)
			{
				return "no Vulkan device matches GL device UUID " + glUuid + " (S2-20)";
			}

			// S2-21: external memory and semaphore extensions
			List<String> required = new ArrayList<>();
			if (platform == InteropSupport.Platform.WINDOWS)
			{
				required.add(KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME);
				required.add(KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME);
			}
			else
			{
				required.add(KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME);
				required.add(KHRExternalSemaphoreFd.VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);
			}
			List<String> available = deviceExtensions(stack, physical);
			List<String> missing = new ArrayList<>();
			for (String ext : required)
			{
				if (!available.contains(ext))
				{
					missing.add(ext);
				}
			}
			if (!missing.isEmpty())
			{
				return "Vulkan device missing " + String.join(", ", missing) + " (S2-21)";
			}

			// One queue that can do graphics and compute (FSR 2/3 dispatches compute work)
			vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
			VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
			vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
			queueFamily = -1;
			for (int i = 0; i < families.capacity(); i++)
			{
				int flags = families.get(i).queueFlags();
				if ((flags & VK_QUEUE_GRAPHICS_BIT) != 0 && (flags & VK_QUEUE_COMPUTE_BIT) != 0)
				{
					queueFamily = i;
					break;
				}
			}
			if (queueFamily < 0)
			{
				return "no graphics+compute queue family";
			}

			VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, stack)
				.sType$Default()
				.queueFamilyIndex(queueFamily)
				.pQueuePriorities(stack.floats(1.0f));
			PointerBuffer extNames = stack.mallocPointer(required.size());
			for (String ext : required)
			{
				extNames.put(stack.UTF8(ext));
			}
			extNames.flip();
			VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack)
				.sType$Default()
				.pQueueCreateInfos(qci)
				.ppEnabledExtensionNames(extNames);
			err = vkCreateDevice(physical, dci, null, pp);
			if (err != VK_SUCCESS)
			{
				return "vkCreateDevice failed: " + err;
			}
			device = new VkDevice(pp.get(0), physical, dci, VK_API_VERSION_1_1);
			enabledDeviceExtensions = required;

			vkGetDeviceQueue(device, queueFamily, 0, pp);
			queue = new VkQueue(pp.get(0), device);
		}
		return null;
	}

	/**
	 * Load the system Vulkan loader (vulkan-1.dll / libvulkan.so.1) if it isn't loaded yet. LWJGL's VK class
	 * loads it by itself the first time the class is used (unless Configuration.VULKAN_EXPLICIT_INIT is set),
	 * so calling VK.create() unconditionally throws "Vulkan has already been created" (8.4). The loader stays
	 * loaded for the life of the process; only Vulkan objects are created and destroyed per device.
	 */
	private static void ensureLoader()
	{
		try
		{
			VK.getFunctionProvider();
		}
		catch (RuntimeException notLoaded)
		{
			VK.create();
		}
	}

	private static boolean hasInstanceLayer(MemoryStack stack, String name)
	{
		IntBuffer n = stack.mallocInt(1);
		vkEnumerateInstanceLayerProperties(n, null);
		VkLayerProperties.Buffer layers = VkLayerProperties.malloc(n.get(0), stack);
		vkEnumerateInstanceLayerProperties(n, layers);
		for (int i = 0; i < layers.capacity(); i++)
		{
			if (name.equals(layers.get(i).layerNameString()))
			{
				return true;
			}
		}
		return false;
	}

	private static List<String> deviceExtensions(MemoryStack stack, VkPhysicalDevice pd)
	{
		IntBuffer n = stack.mallocInt(1);
		vkEnumerateDeviceExtensionProperties(pd, (ByteBuffer) null, n, null);
		VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(n.get(0), stack);
		vkEnumerateDeviceExtensionProperties(pd, (ByteBuffer) null, n, props);
		List<String> names = new ArrayList<>();
		for (int i = 0; i < props.capacity(); i++)
		{
			names.add(props.get(i).extensionNameString());
		}
		return names;
	}

	public VkDevice device()
	{
		return device;
	}

	public VkQueue queue()
	{
		return queue;
	}

	public int queueFamily()
	{
		return queueFamily;
	}

	/** Number of validation messages (warnings and errors) reported so far. */
	public int validationMessages()
	{
		return validationMessages.get();
	}

	/** S2-26, A-07, G-11: destroy the device, messenger and instance (the loader stays loaded). Safe to call twice. */
	@Override
	public void close()
	{
		if (device != null)
		{
			vkDeviceWaitIdle(device);
			vkDestroyDevice(device, null);
			device = null;
			queue = null;
		}
		if (messenger != 0)
		{
			vkDestroyDebugUtilsMessengerEXT(instance, messenger, null);
			messenger = 0;
		}
		if (instance != null)
		{
			vkDestroyInstance(instance, null);
			instance = null;
		}
		if (callback != null)
		{
			callback.free();
			callback = null;
		}
	}
}
