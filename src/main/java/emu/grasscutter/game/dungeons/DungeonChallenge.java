package emu.grasscutter.game.dungeons;

import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.def.DungeonData;
import emu.grasscutter.game.entity.EntityMonster;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.scripts.constants.EventType;
import emu.grasscutter.scripts.data.SceneGroup;
import emu.grasscutter.scripts.data.ScriptArgs;
import emu.grasscutter.server.packet.send.PacketChallengeDataNotify;
import emu.grasscutter.server.packet.send.PacketDungeonChallengeBeginNotify;
import emu.grasscutter.server.packet.send.PacketDungeonChallengeFinishNotify;
import emu.grasscutter.server.packet.send.PacketGadgetAutoPickDropInfoNotify;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.util.ArrayList;
import java.util.List;

public class DungeonChallenge {
	private final Scene scene;
	private final SceneGroup group;
	
	private int challengeIndex;
	private int challengeId;
	private boolean success;
	private boolean progress;
	/**
	 * has more challenge
	 */
	private boolean stage;
	private int score;
	private int objective = 0;
	private IntSet rewardedPlayers;

	public DungeonChallenge(Scene scene, SceneGroup group, int challengeId, int challengeIndex, int objective) {
		this.scene = scene;
		this.group = group;
		this.challengeId = challengeId;
		this.challengeIndex = challengeIndex;
		this.objective = objective;
		this.setRewardedPlayers(new IntOpenHashSet());
	}

	public Scene getScene() {
		return scene;
	}

	public SceneGroup getGroup() {
		return group;
	}
	
	public int getChallengeIndex() {
		return challengeIndex;
	}

	public void setChallengeIndex(int challengeIndex) {
		this.challengeIndex = challengeIndex;
	}

	public int getChallengeId() {
		return challengeId;
	}

	public void setChallengeId(int challengeId) {
		this.challengeId = challengeId;
	}

	public int getObjective() {
		return objective;
	}

	public void setObjective(int objective) {
		this.objective = objective;
	}

	public boolean isSuccess() {
		return success;
	}

	public void setSuccess(boolean isSuccess) {
		this.success = isSuccess;
	}
	
	public boolean inProgress() {
		return progress;
	}

	public int getScore() {
		return score;
	}

	public boolean isStage() {
		return stage;
	}

	public void setStage(boolean stage) {
		this.stage = stage;
	}

	public int getTimeLimit() {
		return 600;
	}

	public IntSet getRewardedPlayers() {
		return rewardedPlayers;
	}

	public void setRewardedPlayers(IntSet rewardedPlayers) {
		this.rewardedPlayers = rewardedPlayers;
	}

	public void start() {
		this.progress = true;
		getScene().broadcastPacket(new PacketDungeonChallengeBeginNotify(this));
	}
	
	public void finish() {
		this.progress = false;
		
		getScene().broadcastPacket(new PacketDungeonChallengeFinishNotify(this));
		
		if (this.isSuccess()) {
			// Call success script event
			this.getScene().getScriptManager().callEvent(EventType.EVENT_CHALLENGE_SUCCESS, null);

			// Settle
			settle();
		} else {
			this.getScene().getScriptManager().callEvent(EventType.EVENT_CHALLENGE_FAIL, null);
		}
	}
	
	private void settle() {
		getScene().getDungeonSettleObservers().forEach(o -> o.onDungeonSettle(getScene()));

		if(!stage){
			getScene().getScriptManager().callEvent(EventType.EVENT_DUNGEON_SETTLE,
					// TODO record the time in PARAM2 and used in action
					new ScriptArgs(this.isSuccess() ? 1 : 0, 100));
		}
	}

	public void onMonsterDie(EntityMonster entity) {
		score = getScore() + 1;
		
		getScene().broadcastPacket(new PacketChallengeDataNotify(this, 1, getScore()));
		
		if (getScore() >= getObjective() && this.progress) {
			this.setSuccess(true);
			finish();
		}
	}
	
	public void getStatueDrops(Player player) {
		DungeonData dungeonData = getScene().getDungeonData();
		if (!isSuccess() || dungeonData == null || dungeonData.getRewardPreview() == null || dungeonData.getRewardPreview().getPreviewItems().length == 0) {
			return;
		}
		
		// Already rewarded
		if (getRewardedPlayers().contains(player.getUid())) {
			return;
		}
		
		List<GameItem> rewards = new ArrayList<>();
		for (ItemParamData param : getScene().getDungeonData().getRewardPreview().getPreviewItems()) {
			rewards.add(new GameItem(param.getId(), Math.max(param.getCount(), 1)));
		}
		
		player.getInventory().addItems(rewards, ActionReason.DungeonStatueDrop);
		player.sendPacket(new PacketGadgetAutoPickDropInfoNotify(rewards));
		
		getRewardedPlayers().add(player.getUid());
	}
}