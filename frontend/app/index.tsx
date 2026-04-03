import { useEffect, useState, useCallback, useRef } from "react";
import { View, Pressable, Text, StyleSheet, Platform, ActivityIndicator } from "react-native";
import LoginScreen from "../screens/LoginScreen";
import RegisterScreen from "../screens/RegisterScreen";
import ForgotPasswordScreen from "../screens/ForgotPasswordScreen";
import DashboardScreen from "../screens/DashboardScreen";
import GPACalculatorScreen from "../screens/GPACalculatorScreen";
import MyCoursesScreen from "../screens/MyCoursesScreen";
import CalendarScreen from "../screens/CalendarScreen";
import SideDrawer from "../screens/components/SideDrawer";
import { getToken, removeToken } from "../src/utils/tokenStorage";
import { API_BASE_URL } from "../src/config/api";

type Screen = "dashboard" | "gpa" | "myCourses" | "calendar";
type AuthScreen = "login" | "register" | "forgotPassword";

const validScreens: Screen[] = ["dashboard", "gpa", "myCourses", "calendar"];

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
    await removeToken();
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
        return true;
      }
    } catch {}
    return false;
  }, []);

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

  if (loggedIn === null) {
    return (
      <View style={{ flex: 1, backgroundColor: "#F5F1F3", justifyContent: "center", alignItems: "center" }}>
        <ActivityIndicator size="large" color="#7A003C" />
      </View>
    );
  }

  if (!loggedIn) {
    // No auth param on web → redirect to landing page
    if (Platform.OS === "web" && authScreen === "login" && !new URLSearchParams(window.location.search).has("auth")) {
      window.location.href = "/";
      return null;
    }
    if (authScreen === "forgotPassword") {
      return <ForgotPasswordScreen onGoToLogin={goToLogin} />;
    }
    if (authScreen === "register") {
      return <RegisterScreen onGoToLogin={goToLogin} />;
    }
    return (
      <LoginScreen
        onLoginSuccess={() => {
          setLoggedIn(true);
          fetchUsername();
        }}
        onGoToRegister={() => setAuthScreen("register")}
        onGoToForgotPassword={() => setAuthScreen("forgotPassword")}
      />
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
      default:
        return (
          <DashboardScreen
            onGoToCalendar={() => navigateTo("calendar")}
            onGoToMyCourses={() => {
              setHighlightDropdowns(true);
              navigateTo("myCourses");
            }}
            onAuthError={logout}
          />
        );
    }
  };

  return (
    <View style={styles.root}>
      {/* Hamburger button */}
      <Pressable
        style={styles.hamburger}
        onPress={() => setDrawerOpen(true)}
      >
        <View style={styles.hamburgerLine} />
        <View style={styles.hamburgerLine} />
        <View style={styles.hamburgerLine} />
      </Pressable>

      {/* Main content */}
      <View style={styles.content}>{renderScreen()}</View>

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

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: "#F5F1F3",
  },
  hamburger: {
    position: "absolute",
    top: Platform.OS === "ios" ? 54 : 14,
    left: 14,
    zIndex: 100,
    padding: 8,
    gap: 4,
  },
  hamburgerLine: {
    width: 22,
    height: 2.5,
    backgroundColor: "#3B0A1D",
    borderRadius: 2,
  },
  content: {
    flex: 1,
    paddingTop: Platform.OS === "ios" ? 44 : 4,
  },
});
