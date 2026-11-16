import { useEffect, useState, useCallback, useRef } from "react";
import { View, Pressable, StyleSheet, Platform } from "react-native";
import { Text } from "../src/ui/Text";
import { radius } from "../src/theme/spacing";
import LoginScreen from "../screens/LoginScreen";
import RegisterScreen from "../screens/RegisterScreen";
import ForgotPasswordScreen from "../screens/ForgotPasswordScreen";
import DashboardScreen, { DashboardSkeleton } from "../screens/DashboardScreen";
import GPACalculatorScreen from "../screens/GPACalculatorScreen";
import MyCoursesScreen from "../screens/MyCoursesScreen";
import CalendarScreen, { CalendarSkeleton } from "../screens/CalendarScreen";
import LeaderboardScreen from "../screens/LeaderboardScreen";
import LeaderboardOnboardingScreen from "../screens/LeaderboardOnboardingScreen";
import SideDrawer from "../screens/components/SideDrawer";
import CalendarReconnectBanner from "../screens/components/CalendarReconnectBanner";
import { endSession, getToken, removeToken } from "../src/utils/tokenStorage";
import { API_BASE_URL } from "../src/config/api";
import { queryClient } from "../src/cache/queryClient";
import { qk } from "../src/cache/keys";
import { useTheme } from "../src/theme/ThemeContext";
import ThemeToggle from "../src/ui/ThemeToggle";
import { Skeleton, SkeletonGroup } from "../src/ui/Skeleton";
import BrandField from "../screens/components/leaderboard/BrandField";
import { PodiumSkeleton } from "../screens/components/leaderboard/Podium";
import { RankRowSkeleton } from "../screens/components/leaderboard/RankRow";

type Screen =
  | "dashboard"
  | "gpa"
  | "myCourses"
  | "calendar"
  | "leaderboard"
  | "leaderboardJoin";
type AuthScreen = "login" | "register" | "forgotPassword";

const validScreens: Screen[] = [
  "dashboard",
  "gpa",
  "myCourses",
  "calendar",
  "leaderboard",
  "leaderboardJoin",
];

/**
 * Screens that draw their own full-bleed header.
 *
 * The global hamburger is absolutely positioned at the top left in dark maroon, which is exactly
 * where the leaderboard puts its own back arrow and exactly the colour that disappears against a
 * maroon field. These screens own that corner instead.
 */
const OWNS_TOP_LEFT: Screen[] = ["leaderboard", "leaderboardJoin"];


function getAuthScreenFromURL(): AuthScreen {
  if (Platform.OS !== "web") return "login";
  const params = new URLSearchParams(window.location.search);
  const auth = params.get("auth");
  if (auth === "register") return "register";
  if (auth === "forgotPassword") return "forgotPassword";
  return "login";
}

function getScreenFromURL(): Screen {
  if (Platform.OS !== "web") return "dashboard";
  const params = new URLSearchParams(window.location.search);
  const s = params.get("screen");
  if (validScreens.includes(s as Screen)) return s as Screen;
  // Support /dashboard path as the base authenticated route
  if (window.location.pathname === "/dashboard") return "dashboard";
  return "dashboard";
}

export default function Home() {
  const c = useTheme();
  const [loggedIn, setLoggedIn] = useState<boolean | null>(null);
  const [authScreen, setAuthScreen] = useState<AuthScreen>(getAuthScreenFromURL);
  const [screen, setScreen] = useState<Screen>(getScreenFromURL);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [username, setUsername] = useState("");
  const [highlightDropdowns, setHighlightDropdowns] = useState(false);
  const isPopState = useRef(false);

  // Sync screen state with browser history (web only)
  const navigateTo = useCallback((s: Screen) => {
    setScreen(s);
    if (Platform.OS === "web" && !isPopState.current) {
      const url = s === "dashboard" ? "/dashboard" : `/dashboard?screen=${s}`;
      window.history.pushState({ screen: s }, "", url);
    }
    isPopState.current = false;
  }, []);

  useEffect(() => {
    if (Platform.OS !== "web") return;
    const handlePopState = (e: PopStateEvent) => {
      isPopState.current = true;
      const s = e.state?.screen;
      setScreen(validScreens.includes(s) ? s : "dashboard");
    };
    window.addEventListener("popstate", handlePopState);
    // Set initial history state
    if (!window.history.state?.screen) {
      const initial = getScreenFromURL();
      const url = initial === "dashboard" ? "/dashboard" : `/dashboard?screen=${initial}`;
      window.history.replaceState({ screen: initial }, "", url);
    }
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  const logout = async () => {
    await endSession();
    // Must run on every logout: without it, account A's cached data is still
    // in memory when account B signs in on the same device.
    queryClient.clear();
    setLoggedIn(false);
    navigateTo("dashboard");
    setUsername("");
  };

  const goToLogin = () => {
    setAuthScreen("login");
  };

  const fetchUsername = useCallback(async () => {
    const token = await getToken();
    if (!token) return false;
    try {
      const res = await fetch(
        `${API_BASE_URL.replace(/\/$/, "")}/api/dashboard`,
        { headers: { Authorization: `Bearer ${token}` } }
      );
      if (res.status === 401 || res.status === 403) {
        await removeToken();
        return false;
      }
      if (res.ok) {
        const data = await res.json();
        setUsername(data.username ?? "");
        // This is the same payload DashboardScreen asks for. Seeding it here
        // means the dashboard's first mount is a cache hit, not a second call.
        queryClient.setQueryData(qk.dashboard, data);
        return true;
      }
    } catch {}
    return false;
  }, []);

  // TermProvider sits above this screen, so its term list is first fetched before anyone is
  // signed in and fails with a 401 that is never retried. Ask again once there is a session,
  // or a new account (nothing stored to fall back on) never gets a selected term.
  useEffect(() => {
    if (loggedIn) queryClient.invalidateQueries({ queryKey: qk.terms });
  }, [loggedIn]);

  useEffect(() => {
    getToken().then(async (token) => {
      if (!token) {
        setLoggedIn(false);
        return;
      }
      const valid = await fetchUsername();
      setLoggedIn(valid);
    });
  }, [fetchUsername]);

  // The light/dark switch floats top right on every screen that does not draw its own header
  // there, mirroring the hamburger on the left. The leaderboard screens carry it in their header.
  const floatingToggle = <ThemeToggle style={styles.themeToggle} />;

  if (loggedIn === null) {
    const onField = OWNS_TOP_LEFT.includes(screen);
    return (
      <View style={[styles.root, { backgroundColor: c.background }]}>
        <View style={styles.content}>
          <ShellSkeleton screen={screen} />
        </View>
        <ThemeToggle tone={onField ? "field" : "ground"} style={styles.themeToggle} />
      </View>
    );
  }

  if (!loggedIn) {
    // No auth param on web → redirect to landing page
    if (!__DEV__ && Platform.OS === "web" && authScreen === "login" && !new URLSearchParams(window.location.search).has("auth")) {
      window.location.href = "/";
      return null;
    }
    const authView =
      authScreen === "forgotPassword" ? (
        <ForgotPasswordScreen onGoToLogin={goToLogin} />
      ) : authScreen === "register" ? (
        <RegisterScreen onGoToLogin={goToLogin} />
      ) : (
        <LoginScreen
          onLoginSuccess={() => {
            setLoggedIn(true);
            fetchUsername();
          }}
          onGoToRegister={() => setAuthScreen("register")}
          onGoToForgotPassword={() => setAuthScreen("forgotPassword")}
        />
      );
    return (
      <View style={[styles.root, { backgroundColor: c.background }]}>
        {authView}
        {floatingToggle}
      </View>
    );
  }

  const renderScreen = () => {
    switch (screen) {
      case "gpa":
        return <GPACalculatorScreen onAuthError={logout} />;
      case "myCourses":
        return (
          <MyCoursesScreen
            onAuthError={logout}
            onBack={() => Platform.OS === "web" ? window.history.back() : navigateTo("dashboard")}
            highlightDropdowns={highlightDropdowns}
            onHighlightComplete={() => setHighlightDropdowns(false)}
          />
        );
      case "calendar":
        return (
          <CalendarScreen
            onAuthError={logout}
            onBack={() => Platform.OS === "web" ? window.history.back() : navigateTo("dashboard")}
          />
        );
      case "leaderboard":
        return (
          <LeaderboardScreen
            onAuthError={logout}
            onBack={() => Platform.OS === "web" ? window.history.back() : navigateTo("dashboard")}
            onJoin={() => navigateTo("leaderboardJoin")}
          />
        );
      case "leaderboardJoin":
        return (
          <LeaderboardOnboardingScreen
            onAuthError={logout}
            // Straight to the board rather than back through the flow they just finished.
            onDone={() => navigateTo("leaderboard")}
            onCancel={() => Platform.OS === "web" ? window.history.back() : navigateTo("dashboard")}
          />
        );
      default:
        return (
          <DashboardScreen
            onGoToCalendar={() => navigateTo("calendar")}
            onGoToMyCourses={() => {
              setHighlightDropdowns(true);
              navigateTo("myCourses");
            }}
            onGoToLeaderboard={() => navigateTo("leaderboard")}
            onJoinLeaderboard={() => navigateTo("leaderboardJoin")}
            onAuthError={logout}
          />
        );
    }
  };

  return (
    <View style={[styles.root, { backgroundColor: c.background }]}>
      {/* Hamburger button. Hidden where the screen draws its own header in that corner. */}
      {!OWNS_TOP_LEFT.includes(screen) && (
      <Pressable
        style={styles.hamburger}
        onPress={() => setDrawerOpen(true)}
        accessibilityRole="button"
        accessibilityLabel="Open menu"
      >
        <View style={[styles.hamburgerLine, { backgroundColor: c.boldText }]} />
        <View style={[styles.hamburgerLine, { backgroundColor: c.boldText }]} />
        <View style={[styles.hamburgerLine, { backgroundColor: c.boldText }]} />
      </Pressable>
      )}

      {/* Main content */}
      <View style={styles.content}>{renderScreen()}</View>

      {!OWNS_TOP_LEFT.includes(screen) && floatingToggle}

      {/* Calendar connection warning, on every screen. At the bottom so it never sits under the
          hamburger or a screen's own full-bleed header. */}
      <CalendarReconnectBanner />

      {/* Side drawer overlay */}
      <SideDrawer
        visible={drawerOpen}
        activeScreen={screen}
        username={username}
        onNavigate={(s) => navigateTo(s as Screen)}
        onClose={() => setDrawerOpen(false)}
        onLogout={logout}
      />
    </View>
  );
}

/**
 * What the shell shows while it checks a stored session. That check is one request, but on a cold
 * server it can take seconds, so it previews the screen the URL is heading to rather than one
 * generic page: the real screen then fills in over the same layout instead of replacing it.
 */
function ShellSkeleton({ screen }: { screen: Screen }) {
  const c = useTheme();
  if (screen === "dashboard") return <DashboardSkeleton />;
  if (screen === "calendar") return <CalendarSkeleton />;
  if (screen === "leaderboard" || screen === "leaderboardJoin") {
    return (
      <SkeletonGroup label="Loading the leaderboard" style={{ flex: 1 }}>
        <BrandField style={styles.skelField}>
          <Skeleton width={140} height={20} tone="field" style={{ alignSelf: "center" }} />
          <Skeleton width={110} height={12} tone="field" style={{ alignSelf: "center", marginTop: 14 }} />
          <View style={{ marginTop: 18 }}>
            <PodiumSkeleton />
          </View>
        </BrandField>
        <View style={[styles.skelSheet, { backgroundColor: c.background }]}>
          {["46%", "58%", "38%", "52%", "44%"].map((w) => (
            <RankRowSkeleton key={w} handleWidth={w as `${number}%`} />
          ))}
        </View>
      </SkeletonGroup>
    );
  }
  // My Courses and the GPA calculator: a centred title over a stack of cards.
  return (
    <SkeletonGroup label="Loading" style={styles.skelPage}>
      <Skeleton width={180} height={28} style={{ alignSelf: "center" }} />
      <Skeleton width={260} height={14} style={{ alignSelf: "center", marginTop: 10, marginBottom: 28 }} />
      {[0, 1, 2].map((i) => (
        <View key={i} style={[styles.skelCard, { backgroundColor: c.surface, borderColor: c.borderSubtle }]}>
          <Skeleton width={i === 1 ? "36%" : "44%"} height={20} />
          <Skeleton width="62%" height={14} style={{ marginTop: 10 }} />
        </View>
      ))}
    </SkeletonGroup>
  );
}

const styles = StyleSheet.create({
  skelField: {
    paddingTop: Platform.OS === "ios" ? 8 : 18,
    paddingHorizontal: 16,
  },
  skelSheet: {
    borderTopLeftRadius: radius.lg,
    borderTopRightRadius: radius.lg,
    marginTop: -18,
    paddingTop: 14,
  },
  skelPage: {
    padding: 24,
    paddingTop: 36,
    width: "100%",
    maxWidth: 1360,
    alignSelf: "center",
  },
  skelCard: {
    borderRadius: radius.lg,
    borderWidth: 1,
    padding: 24,
    marginBottom: 24,
  },
  root: {
    flex: 1,
  },
  hamburger: {
    position: "absolute",
    top: Platform.OS === "ios" ? 54 : 14,
    left: 14,
    zIndex: 100,
    padding: 8,
    gap: 4,
    borderRadius: radius.md,
  },
  themeToggle: {
    position: "absolute",
    top: Platform.OS === "ios" ? 52 : 10,
    right: 12,
    zIndex: 100,
  },
  hamburgerLine: {
    width: 22,
    height: 2.5,
    borderRadius: 2,
  },
  content: {
    flex: 1,
    paddingTop: Platform.OS === "ios" ? 44 : 4,
  },
});
