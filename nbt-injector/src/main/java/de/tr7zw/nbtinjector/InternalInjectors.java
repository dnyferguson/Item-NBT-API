package de.tr7zw.nbtinjector;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.Bukkit;

import de.tr7zw.changeme.nbtapi.NbtApiException;
import de.tr7zw.changeme.nbtapi.utils.MinecraftVersion;
import de.tr7zw.changeme.nbtapi.utils.nmsmappings.ClassWrapper;
import de.tr7zw.changeme.nbtapi.utils.nmsmappings.ReflectionMethod;
import de.tr7zw.nbtinjector.NBTInjector.Entity;
import de.tr7zw.nbtinjector.NBTInjector.TileEntity;
import javassist.ClassPool;
import static de.tr7zw.changeme.nbtapi.utils.MinecraftVersion.logger;

/**
 * Contains the internal methods for different Minecraft Versions of injecting
 * the Entities/Tiles
 * 
 * @author tr7zw
 *
 */
@SuppressWarnings("unchecked")
public class InternalInjectors {

	private static final List<String> skippingEntities = Arrays.asList("minecraft:player", "minecraft:fishing_bobber",
			"minecraft:lightning_bolt"); // These are broken/won't work, used by 1.14+
	private static final Map<String, String> classMappings = new HashMap<>();

	static {
		classMappings.put("minecraft:wandering_trader", "VillagerTrader");
		classMappings.put("minecraft:trader_llama", "LlamaTrader");
		classMappings.put("minecraft:area_effect_cloud", "AreaEffectCloud");
		classMappings.put("minecraft:donkey", "HorseDonkey");
		classMappings.put("minecraft:ender_dragon", "EnderDragon");
		classMappings.put("minecraft:skeleton_horse", "HorseSkeleton");
		classMappings.put("minecraft:fireball", "LargeFireball");
		classMappings.put("minecraft:mule", "HorseMule");
		classMappings.put("minecraft:zombie_horse", "HorseZombie");
		classMappings.put("minecraft:mooshroom", "MushroomCow");
	}

	/**
	 * Hidden constructor
	 */
	private InternalInjectors() {

	}

	protected static void entity1v10Below(ClassPool classPool) throws ReflectiveOperationException {
		for (Map.Entry<String, Class<?>> entry : new HashSet<>(Entity.getCMap().entrySet())) {
			try {
				if (INBTWrapper.class.isAssignableFrom(entry.getValue())) {
					continue;
				} // Already injected
				int entityId = Entity.getFMap().get(entry.getValue());

				Class<?> wrapped = ClassGenerator.wrapEntity(classPool, entry.getValue(), "__extraData");
				Entity.getCMap().put(entry.getKey(), wrapped);
				Entity.getDMap().put(wrapped, entry.getKey());

				Entity.getEMap().put(entityId, wrapped);
				Entity.getFMap().put(wrapped, entityId);
			} catch (Exception e) {
				throw new NbtApiException("Exception while injecting " + entry.getKey(), e);
			}
		}
	}

	protected static void entity1v12Below(ClassPool classPool) throws ReflectiveOperationException {
		Object registry = Entity.getRegistry();
		Map<Object, Object> inverse = new HashMap<>();
		Set<?> it = new HashSet<>((Set<?>) ReflectionMethod.REGISTRY_KEYSET.run(registry));
		for (Object mckey : it) {
			Class<?> tileclass = (Class<?>) ReflectionMethod.REGISTRY_GET.run(registry, mckey);
			inverse.put(tileclass, mckey);
			try {
				if (INBTWrapper.class.isAssignableFrom(tileclass)) {
					continue;
				} // Already injected
				Class<?> wrapped = ClassGenerator.wrapEntity(classPool, tileclass, "__extraData");
				ReflectionMethod.REGISTRY_SET.run(registry, mckey, wrapped);
				inverse.put(wrapped, mckey);
			} catch (Exception e) {
				throw new NbtApiException("Exception while injecting " + mckey, e);
			}
		}
		Field inverseField = registry.getClass().getDeclaredField("b");
		NBTInjector.setFinal(registry, inverseField, inverse);
	}

	protected static void entity1v13Below(ClassPool classPool) throws ReflectiveOperationException {
		Object entityRegistry = ClassWrapper.NMS_IREGISTRY.getClazz().getField("ENTITY_TYPE").get(null);
		Set<?> registryentries = new HashSet<>((Set<?>) ReflectionMethod.REGISTRYMATERIALS_KEYSET.run(entityRegistry));
		for (Object mckey : registryentries) {
			Object entityTypesObj = ReflectionMethod.REGISTRYMATERIALS_GET.run(entityRegistry, mckey);
			Field supplierField = entityTypesObj.getClass().getDeclaredField("aT");
			Field classField = entityTypesObj.getClass().getDeclaredField("aS");
			classField.setAccessible(true);
			supplierField.setAccessible(true);
			Function<Object, Object> function = (Function<Object, Object>) supplierField.get(entityTypesObj);
			Class<?> nmsclass = (Class<?>) classField.get(entityTypesObj);
			try {
				if (INBTWrapper.class.isAssignableFrom(nmsclass)) {
					continue;
				} // Already injected
				Class<?> wrapped = ClassGenerator.wrapEntity(classPool, nmsclass, "__extraData");
				NBTInjector.setFinal(entityTypesObj, classField, wrapped);
				NBTInjector.setFinal(entityTypesObj, supplierField, new Function<Object, Object>() {

					@Override
					public Object apply(Object t) {
						try {
							return wrapped.getConstructor(ClassWrapper.NMS_WORLD.getClazz()).newInstance(t);
						} catch (Exception ex) {
							logger.log(Level.SEVERE, "Error while creating custom entity instance! ", ex);
							return function.apply(t);// Fallback to the original one
						}
					}
				});
			} catch (Exception e) {
				throw new NbtApiException("Exception while injecting " + mckey, e);
			}
		}
	}

	protected static void entity1v14(ClassPool classPool) throws ReflectiveOperationException {
		Object entityRegistry = ClassWrapper.NMS_IREGISTRY.getClazz().getField("ENTITY_TYPE").get(null);
		Set<?> registryentries = new HashSet<>((Set<?>) ReflectionMethod.REGISTRYMATERIALS_KEYSET.run(entityRegistry));
		for (Object mckey : registryentries) {
			if (skippingEntities.contains(mckey.toString())) {
				logger.info("Skipping, won't be able add NBT to '" + mckey + "' entities!");
				continue;
			}
			Object entityTypesObj = ReflectionMethod.REGISTRYMATERIALS_GET.run(entityRegistry, mckey);
			Field creatorField = entityTypesObj.getClass().getDeclaredField("aZ");
			creatorField.setAccessible(true);
			Object creator = creatorField.get(entityTypesObj);
			Method createEntityMethod = creator.getClass().getMethod("create", ClassWrapper.NMS_ENTITYTYPES.getClazz(),
					ClassWrapper.NMS_WORLD.getClazz());
			createEntityMethod.setAccessible(true);
			Class<?> nmsclass = null;
			try {
				nmsclass = createEntityMethod.invoke(creator, entityTypesObj, null).getClass();
			} catch (Exception ignore) {
				// ignore
			}
			if (nmsclass == null) {
				String version = Bukkit.getServer().getClass().getPackage().getName().replace(".", ",").split(",")[3];
				String name = mckey.toString().replace("minecraft:", "");
				name = name.substring(0, 1).toUpperCase() + name.substring(1);
				name = "Entity" + name;
				if (classMappings.containsKey(mckey.toString()))
					name = "Entity" + classMappings.get(mckey.toString());
				try {
					nmsclass = Class.forName("net.minecraft.server." + version + "." + name);
				} catch (Exception ignore) {
					logger.info("Not found: " + "net.minecraft.server." + version + "." + name);
					// ignore
				}
			}
			if (nmsclass == null) {
				logger.info(
						"Wasn't able to create an Entity instace, won't be able add NBT to '" + mckey + "' entities!");
				continue;
			}
			try {
				if (INBTWrapper.class.isAssignableFrom(nmsclass)) {
					continue;
				} // Already injected
				Class<?> wrapped = ClassGenerator.wrapEntity(classPool, nmsclass, "__extraData");
				NBTInjector.setFinal(entityTypesObj, creatorField,
						ClassGenerator.createEntityTypeWrapper(classPool, wrapped).newInstance());
			} catch (Exception e) {
				throw new NbtApiException("Exception while injecting " + mckey, e);
			}
		}
	}

	protected static void tile1v10Below(ClassPool classPool) throws ReflectiveOperationException {
		for (Map.Entry<String, Class<?>> entry : new HashSet<>(TileEntity.getFMap().entrySet())) {
			try {
				if (INBTWrapper.class.isAssignableFrom(entry.getValue())) {
					continue;
				} // Already injected
				Class<?> wrapped = ClassGenerator.wrapTileEntity(classPool, entry.getValue(), "__extraData");
				TileEntity.getFMap().put(entry.getKey(), wrapped);
				TileEntity.getGMap().put(wrapped, entry.getKey());
			} catch (Exception e) {
				throw new NbtApiException("Exception while injecting " + entry.getKey(), e);
			}
		}
	}

	protected static void tile1v12Below(ClassPool classPool) throws ReflectiveOperationException {
		Object registry = TileEntity.getRegistry();
		Map<Object, Object> inverse = new HashMap<>();
		Set<?> it = new HashSet<>((Set<?>) ReflectionMethod.REGISTRY_KEYSET.run(registry));
		for (Object mckey : it) {
			Class<?> tileclass = (Class<?>) ReflectionMethod.REGISTRY_GET.run(registry, mckey);
			inverse.put(tileclass, mckey);
			try {
				if (INBTWrapper.class.isAssignableFrom(tileclass)) {
					continue;
				} // Already injected
				Class<?> wrapped = ClassGenerator.wrapTileEntity(classPool, tileclass, "__extraData");
				ReflectionMethod.REGISTRY_SET.run(registry, mckey, wrapped);
				inverse.put(wrapped, mckey);
			} catch (Exception e) {
				throw new NbtApiException("Exception while injecting " + mckey, e);
			}
		}
		Field inverseField = registry.getClass().getDeclaredField("b");
		NBTInjector.setFinal(registry, inverseField, inverse);
	}

	protected static void tile1v13(ClassPool classPool) throws ReflectiveOperationException {
		Object tileRegistry = ClassWrapper.NMS_IREGISTRY.getClazz().getField("BLOCK_ENTITY_TYPE").get(null);
		Set<?> registryentries = new HashSet<>((Set<?>) ReflectionMethod.REGISTRYMATERIALS_KEYSET.run(tileRegistry));
		for (Object mckey : registryentries) {
			Object tileEntityTypesObj = ReflectionMethod.REGISTRYMATERIALS_GET.run(tileRegistry, mckey);
			String supplierFieldName = "A";
			if (MinecraftVersion.getVersion().getVersionId() >= MinecraftVersion.MC1_14_R1.getVersionId())
				supplierFieldName = "H";
			Field supplierField = tileEntityTypesObj.getClass().getDeclaredField(supplierFieldName);
			supplierField.setAccessible(true);
			Supplier<Object> supplier = (Supplier<Object>) supplierField.get(tileEntityTypesObj);
			Class<?> nmsclass = supplier.get().getClass();
			try {
				if (INBTWrapper.class.isAssignableFrom(nmsclass)) {
					continue;
				} // Already injected
				Class<?> wrapped = ClassGenerator.wrapTileEntity(classPool, nmsclass, "__extraData");
				NBTInjector.setFinal(tileEntityTypesObj, supplierField, new Supplier<Object>() {
					@Override
					public Object get() {
						try {
							return wrapped.newInstance();
						} catch (InstantiationException | IllegalAccessException e) {
							logger.log(Level.SEVERE, "Error while creating custom tile instance! ", e);
							return supplier.get(); // Use the original one as fallback
						}
					}
				});
			} catch (Exception e) {
				throw new NbtApiException("Exception while injecting " + mckey, e);
			}
		}
	}

}