package emu.grasscutter.scripts.data;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.utils.Position;
import lombok.Setter;
import lombok.ToString;

import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.ScriptException;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static emu.grasscutter.Configuration.SCRIPT;

@ToString
@Setter
public class SceneGroup {
	public transient int block_id; // Not an actual variable in the scripts but we will keep it here for reference
	
	public int id;
	public int refresh_id;
	public Position pos;

	public Map<Integer,SceneMonster> monsters; // <ConfigId, Monster>
	public Map<Integer, SceneGadget> gadgets; // <ConfigId, Gadgets>
	public Map<String, SceneTrigger> triggers;

	public List<SceneRegion> regions;
	public List<SceneSuite> suites;
	public List<SceneVar> variables;
	
	public SceneBusiness business;
	public SceneGarbage garbages;
	public SceneInitConfig init_config;

	private transient boolean loaded; // Not an actual variable in the scripts either
	private transient CompiledScript script;
	private transient Bindings bindings;

	public boolean isLoaded() {
		return loaded;
	}
	
	public void setLoaded(boolean loaded) {
		this.loaded = loaded;
	}
	
	public int getBusinessType() {
		return this.business == null ? 0 : this.business.type;
	}
	
	public List<SceneGadget> getGarbageGadgets() {
		return this.garbages == null ? null : this.garbages.gadgets;
	}

	public CompiledScript getScript() {
		return script;
	}

	public SceneSuite getSuiteByIndex(int index) {
		return suites.get(index - 1);
	}

	public Bindings getBindings() {
		return bindings;
	}

	public SceneGroup load(int sceneId){
		if(loaded){
			return this;
		}
		// Set flag here so if there is no script, we dont call this function over and over again.
		setLoaded(true);

		this.bindings = ScriptLoader.getEngine().createBindings();

		CompiledScript cs = ScriptLoader.getScriptByPath(
				SCRIPT("Scene/" + sceneId + "/scene" + sceneId + "_group" + id + "." + ScriptLoader.getScriptType()));

		if (cs == null) {
			return this;
		}

		this.script = cs;
		
		// Eval script
		try {
			cs.eval(bindings);

			// Set
			monsters = ScriptLoader.getSerializer().toList(SceneMonster.class, bindings.get("monsters")).stream()
					.collect(Collectors.toMap(x -> x.config_id, y -> y));
			monsters.values().forEach(m -> m.groupId = id);

			gadgets = ScriptLoader.getSerializer().toList(SceneGadget.class, bindings.get("gadgets")).stream()
					.collect(Collectors.toMap(x -> x.config_id, y -> y));
			gadgets.values().forEach(m -> m.groupId = id);

			triggers = ScriptLoader.getSerializer().toList(SceneTrigger.class, bindings.get("triggers")).stream()
					.collect(Collectors.toMap(x -> x.name, y -> y));
			triggers.values().forEach(t -> t.currentGroup = this);

			suites = ScriptLoader.getSerializer().toList(SceneSuite.class, bindings.get("suites"));
			regions = ScriptLoader.getSerializer().toList(SceneRegion.class, bindings.get("regions"));
			init_config = ScriptLoader.getSerializer().toObject(SceneInitConfig.class, bindings.get("init_config"));
			
			// Garbages TODO fix properly later
			Object garbagesValue = bindings.get("garbages");
			if (garbagesValue != null && garbagesValue instanceof LuaValue garbagesTable) {
				garbages = new SceneGarbage();
				if (garbagesTable.checktable().get("gadgets") != LuaValue.NIL) {
					garbages.gadgets = ScriptLoader.getSerializer().toList(SceneGadget.class, garbagesTable.checktable().get("gadgets").checktable());
				}
			}
			
			// Add variables to suite
			variables = ScriptLoader.getSerializer().toList(SceneVar.class, bindings.get("variables"));

			// Add monsters and gadgets to suite
			for (SceneSuite suite : suites) {
				suite.sceneMonsters = new ArrayList<>(
						suite.monsters.stream()
						.filter(monsters::containsKey)
						.map(monsters::get)
						.toList()
				);

				suite.sceneGadgets = new ArrayList<>(
						suite.gadgets.stream()
								.filter(gadgets::containsKey)
								.map(gadgets::get)
								.toList()
				);

				suite.sceneTriggers = new ArrayList<>(
						suite.triggers.stream()
								.filter(triggers::containsKey)
								.map(triggers::get)
								.toList()
				);
			}

		} catch (NullPointerException e) {
			Grasscutter.getLogger().error("NullPointerException: Error loading group " + id + " in scene " + sceneId,e);	
		} catch (ScriptException e) {
			Grasscutter.getLogger().error("ScriptException: Error loading group " + id + " in scene " + sceneId,e);		
		} catch (Exception e) {
			Grasscutter.getLogger().error("Exception: Error loading group " + id + " in scene " + sceneId);
		}
		
		Grasscutter.getLogger().debug("group {} in scene {} is loaded successfully123", id, sceneId);
		return this;
	}
}
