package utils;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;


import net.minecraft.util.math.BlockPos;

/** Code Originally Written by bebeli555
 * updated by lawnguy
 *
 * honestly was thinking weather or not to keep this code by I like having it all in one class props to bebeli
 */

public class BaritoneUtils {

    /**
     * makes baritone go to a block
     * @param pos a player or block position
     */
	public static void goTo(BlockPos pos)
    {
		getBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(pos.getX(), pos.getY(), pos.getZ()));
	}

    /***
     * Cancels the current baritone task
     */
	public static void forceCancel()
    {
		getBaritone().getPathingBehavior().forceCancel();
	}


    /**
     * makes baritone go near a block within a given radius
     * @param pos target position
     * @param radius how close to get
     */
    public static void goToNear(BlockPos pos, int radius)
    {
        getBaritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, radius));
    }

    /***
     * Checks is baritone is currently pathing
     * @return true or false if B is pathing
     */
	public static boolean isPathing()
    {
		return getBaritone().getPathingBehavior().isPathing();
	}

    /***
     * Gets the current Baritone instance
     * @return the current baritone stance
     */
	public static IBaritone getBaritone()
    {
		return BaritoneAPI.getProvider().getPrimaryBaritone();
	}
}
