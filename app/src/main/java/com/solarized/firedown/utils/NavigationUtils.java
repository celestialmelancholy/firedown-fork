package com.solarized.firedown.utils;

import android.os.Bundle;
import android.util.Log;

import androidx.annotation.IdRes;
import androidx.navigation.NavAction;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.NavDirections;
import androidx.navigation.NavGraph;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.FragmentNavigator;

import com.solarized.firedown.R;


public abstract class NavigationUtils {

    private static final String TAG = NavigationUtils.class.getName();

    /**
     * Shared NavOptions for opening the tab tray. ONLY the tray fades in
     * (250ms, Firefox-style); the exiting screen (browser/home) is removed
     * INSTANTLY with no exit anim.
     *
     * <p>Why no exit fade: the old OPEN_TABS_FADE also applied
     * {@code fade_out} to the browser, which kept the page visible and
     * fading BEHIND the semi-transparent tray for ~250ms, then snapped
     * away when the tray's opaque background landed — the "page stays
     * under the UI then disappears with a snap" bug. The original
     * firedown had no exit anim (browser replaced instantly, no snap);
     * we keep the tray's fade-in and drop the browser's fade-out.</p>
     */
    public static final NavOptions OPEN_TABS_FADE = new NavOptions.Builder()
            .setEnterAnim(R.anim.fade_in)
            .build();

    /**
     * Shared NavOptions for returning from the tab tray to home: the tray
     * fades out while the home screen fades in (Firefox's reverse fade).
     * Used by the nav-graph actions (see nav_graph.xml) and by any
     * programmatic tabs→home navigation.
     */
    public static final NavOptions CLOSE_TABS_FADE_HOME = new NavOptions.Builder()
            .setEnterAnim(R.anim.fade_in)
            .setPopExitAnim(R.anim.fade_out)
            .build();

    /**
     * This function will check navigation safety before starting navigation using direction
     *
     * @param navController NavController instance
     * @param direction     navigation operation
     */
    public static void navigateSafe(NavController navController, NavDirections direction) {

        if(navController == null)
            return;

        NavDestination currentDestination = navController.getCurrentDestination();

        if (currentDestination != null) {
            NavAction navAction = currentDestination.getAction(direction.getActionId());

            if (navAction != null) {
                int destinationId = navAction.getDestinationId();

                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (destinationId != 0 && currentNode != null && currentNode.findNode(destinationId) != null) {
                    navController.navigate(direction);
                }
            }
        }
    }


    public static boolean checkCurrentDestination(NavController navController, int currentId){
        if(navController == null)
            return false;

        NavDestination currentDestination = navController.getCurrentDestination();

        if (currentDestination != null) {
            int id = currentDestination.getId();
            return id == currentId;
        }

        return false;
    }

    public static void navigateSafe(NavController navController, @IdRes int resId, @IdRes int currentId) {

        if(navController == null)
            return;

        NavDestination currentDestination = navController.getCurrentDestination();

        if (currentDestination != null) {

            int id = currentDestination.getId();

            if(id != currentId)
                return;

            NavAction navAction = currentDestination.getAction(resId);

            if (navAction != null) {
                int destinationId = navAction.getDestinationId();

                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (destinationId != 0 && currentNode != null && currentNode.findNode(destinationId) != null) {
                    navController.navigate(resId);
                }
            }else{
                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (currentNode != null && currentNode.findNode(resId) != null) {
                    navController.navigate(resId);
                }
            }
        }
    }

    public static void navigateSafe(NavController navController, @IdRes int resId) {

        if(navController == null)
            return;

        NavDestination currentDestination = navController.getCurrentDestination();

        if (currentDestination != null) {

            Log.d(TAG, "navigateSafe currentDestination: " + currentDestination.toString());

            NavAction navAction = currentDestination.getAction(resId);

            if (navAction != null) {
                int destinationId = navAction.getDestinationId();

                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (destinationId != 0 && currentNode != null && currentNode.findNode(destinationId) != null) {
                    navController.navigate(resId);
                }
            }else{
                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (currentNode != null && currentNode.findNode(resId) != null) {
                    navController.navigate(resId);
                }
            }
        }else{

            Log.e(TAG, "navigateSafe currentDestination is null");
        }
    }


    /**
     * This function will check navigation safety before starting navigation using resId and args bundle
     *
     * @param navController NavController instance
     * @param resId         destination resource id
     * @param args          bundle args
     */
    public static void navigateSafe(NavController navController, @IdRes int resId, Bundle args) {

        if(navController == null)
            return;

        NavDestination currentDestination = navController.getCurrentDestination();

        if (currentDestination != null) {
            NavAction navAction = currentDestination.getAction(resId);

            if (navAction != null) {
                int destinationId = navAction.getDestinationId();

                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (destinationId != 0 && currentNode != null && currentNode.findNode(destinationId) != null) {
                    navController.navigate(resId, args);
                }
            }else{
                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (currentNode != null && currentNode.findNode(resId) != null) {
                    navController.navigate(resId, args);
                }
            }
        }
    }


    public static void navigateSafe(NavController navController, @IdRes int resId, int currentId, Bundle args) {

        if(navController == null)
            return;

        NavDestination currentDestination = navController.getCurrentDestination();

        if (currentDestination != null) {
            NavAction navAction = currentDestination.getAction(resId);

            int id = currentDestination.getId();

            if(id != currentId)
                return;

            if (navAction != null) {
                int destinationId = navAction.getDestinationId();

                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (destinationId != 0 && currentNode != null && currentNode.findNode(destinationId) != null) {
                    navController.navigate(resId, args);
                }
            }else{
                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (currentNode != null && currentNode.findNode(resId) != null) {
                    navController.navigate(resId, args);
                }
            }
        }
    }

    /**
     * Like {@link #navigateSafe(NavController, int, int, Bundle)} but forwards
     * a {@link NavOptions} bundle (for navigation transitions) instead of
     * navigating without options.
     */
    public static void navigateSafe(NavController navController, @IdRes int resId,
                                    int currentId, Bundle args, NavOptions navOptions) {

        if (navController == null)
            return;

        NavDestination currentDestination = navController.getCurrentDestination();

        if (currentDestination != null) {
            int id = currentDestination.getId();

            if (id != currentId)
                return;

            NavAction navAction = currentDestination.getAction(resId);

            NavGraph currentNode = currentDestination instanceof NavGraph
                    ? (NavGraph) currentDestination
                    : currentDestination.getParent();

            if (navAction != null) {
                int destinationId = navAction.getDestinationId();
                if (destinationId != 0 && currentNode != null && currentNode.findNode(destinationId) != null) {
                    navController.navigate(resId, args, navOptions);
                }
            } else if (currentNode != null && currentNode.findNode(resId) != null) {
                navController.navigate(resId, args, navOptions);
            }
        }
    }


    public static void navigateSafe(NavController navController, @IdRes int resId, Bundle args, FragmentNavigator.Extras extras) {

        if(navController == null)
            return;

        NavDestination currentDestination = navController.getCurrentDestination();

        if (currentDestination != null) {
            NavAction navAction = currentDestination.getAction(resId);

            if (navAction != null) {
                int destinationId = navAction.getDestinationId();

                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (destinationId != 0 && currentNode != null && currentNode.findNode(destinationId) != null) {
                    navController.navigate(resId, args, null, extras);
                }
            }else{
                NavGraph currentNode;
                if (currentDestination instanceof NavGraph)
                    currentNode = (NavGraph) currentDestination;
                else
                    currentNode = currentDestination.getParent();

                if (currentNode != null && currentNode.findNode(resId) != null) {
                    navController.navigate(resId, args,null, extras);
                }
            }
        }
    }

    /**
     * Variant that forwards a {@link NavOptions} bundle (typically with
     * a {@code setPopUpTo} target). Same destination-existence guard
     * as the other overloads so a double-tap can't race the navigation
     * into a destination the current node no longer recognises.
     */
    public static void navigateSafe(NavController navController, @IdRes int resId,
                                    Bundle args, NavOptions navOptions) {

        if (navController == null)
            return;

        NavDestination currentDestination = navController.getCurrentDestination();
        if (currentDestination == null)
            return;

        NavAction navAction = currentDestination.getAction(resId);
        NavGraph currentNode = currentDestination instanceof NavGraph
                ? (NavGraph) currentDestination
                : currentDestination.getParent();

        if (navAction != null) {
            int destinationId = navAction.getDestinationId();
            if (destinationId != 0 && currentNode != null && currentNode.findNode(destinationId) != null) {
                navController.navigate(resId, args, navOptions);
            }
        } else if (currentNode != null && currentNode.findNode(resId) != null) {
            navController.navigate(resId, args, navOptions);
        }
    }

    /**
     * Enter the browser while enforcing the single-home / single-browser
     * invariant: pop everything above the active home (a stale browser,
     * the tabs switcher, a bookmarks / history list) and push one browser,
     * yielding {@code [home, browser]}.
     *
     * <p>No-op when already on the browser — the live fragment loads the
     * new content through its BrowserURIViewModel observer, so re-navigating
     * would needlessly tear it down and recreate it.</p>
     *
     * <p>{@code incognito} selects which home anchors the pop, so the
     * regular and incognito stacks each stay {@code [home*, browser]}.</p>
     */
    public static void navigateToBrowser(NavController navController, boolean incognito) {
        navigateToBrowser(navController, incognito, 0, 0, 0, 0);
    }

    /**
     * Like {@link #navigateToBrowser(NavController, boolean)} but with
     * explicit enter/exit/pop-enter/pop-exit animation resources (0 = none).
     * Used by the tab-switcher "+" so a brand-new tab opens into the browser
     * with a visible transition instead of an instant snap.
     */
    public static void navigateToBrowser(NavController navController, boolean incognito,
                                         int enterAnim, int exitAnim,
                                         int popEnterAnim, int popExitAnim) {
        if (navController == null) return;
        NavDestination dest = navController.getCurrentDestination();
        if (dest == null || dest.getId() == R.id.browser) return;
        // Pop up to whichever home is actually on the back stack — prefer the
        // tab's mode, but fall back to the other home (cross-mode tab pick from
        // the switcher) so we never popUpTo a destination that isn't present
        // (which is undefined behaviour). If neither is on the stack, just push.
        int preferred = incognito ? R.id.home_incognito : R.id.home;
        int other = incognito ? R.id.home : R.id.home_incognito;
        int popTarget = onBackStack(navController, preferred) ? preferred
                : (onBackStack(navController, other) ? other : 0);
        NavOptions.Builder opts = new NavOptions.Builder().setLaunchSingleTop(true);
        if (popTarget != 0) opts.setPopUpTo(popTarget, false);
        if (enterAnim != 0) opts.setEnterAnim(enterAnim);
        if (exitAnim != 0) opts.setExitAnim(exitAnim);
        if (popEnterAnim != 0) opts.setPopEnterAnim(popEnterAnim);
        if (popExitAnim != 0) opts.setPopExitAnim(popExitAnim);
        navController.navigate(R.id.browser, null, opts.build());
    }

    /** True if {@code destId} is currently on the back stack. */
    private static boolean onBackStack(NavController navController, int destId) {
        try {
            navController.getBackStackEntry(destId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Return to the active home while enforcing the invariant. If the
     * target home is already on the back stack, pop straight to it —
     * clearing any browser / tabs / list / duplicate sitting above. If it
     * isn't (we're in the other mode), clear the whole stack and make the
     * target home the new root. Replaces the per-fragment popToCorrectHome /
     * navigateToHomeIfNeeded logic, whose fallback used to *push* a second
     * home onto the stack.
     */
    public static void navigateToHome(NavController navController, boolean incognito) {
        if (navController == null) return;
        NavDestination dest = navController.getCurrentDestination();
        if (dest == null) return;
        int target = incognito ? R.id.home_incognito : R.id.home;
        if (dest.getId() == target) return;
        if (navController.popBackStack(target, false)) return;
        NavOptions opts = new NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setPopUpTo(R.id.nav_graph, true)
                .build();
        navController.navigate(target, null, opts);
    }

    public static void popBackStackSafe(NavController navController, int id){

        if(navController == null)
            return;

        NavDestination navDestination = navController.getCurrentDestination();
        if(navDestination != null){
            int currentId = navDestination.getId();
            if(id == currentId) navController.popBackStack();
        }
    }

    private static int orEmpty(Integer value) {
        return value == null ? 0 : value;
    }
}
