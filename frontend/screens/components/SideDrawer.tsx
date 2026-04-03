import React, { useEffect, useState } from "react";
import {
  View,
  Text,
  Pressable,
  StyleSheet,
  Dimensions,
  Platform,
} from "react-native";
import { Feather } from "@expo/vector-icons";
import OnTrackLogo from "./OnTrackLogo";
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withTiming,
  Easing,
  runOnJS,
} from "react-native-reanimated";
import { colors } from "../../src/theme/colors";

const DRAWER_WIDTH = 260;
const SCREEN_WIDTH = Dimensions.get("window").width;

type NavItem = {
  key: string;
  label: string;
  icon: keyof typeof Feather.glyphMap;
};

const NAV_ITEMS: NavItem[] = [
  { key: "dashboard", label: "Dashboard", icon: "grid" },
  { key: "myCourses", label: "My Courses", icon: "book-open" },
  { key: "calendar", label: "Calendar", icon: "calendar" },
  { key: "gpa", label: "GPA Calculator", icon: "bar-chart-2" },
];

type Props = {
  visible: boolean;
  activeScreen: string;
  username: string;
  onNavigate: (screen: string) => void;
  onClose: () => void;
  onLogout: () => void;
};

export default function SideDrawer({
  visible,
  activeScreen,
  username,
  onNavigate,
  onClose,
  onLogout,
}: Props) {
  const [hoveredKey, setHoveredKey] = useState<string | null>(null);
  const translateX = useSharedValue(-DRAWER_WIDTH);
  const backdropOpacity = useSharedValue(0);

  useEffect(() => {
    if (visible) {
      translateX.value = withTiming(0, {
        duration: 250,
        easing: Easing.out(Easing.cubic),
      });
      backdropOpacity.value = withTiming(0.4, { duration: 250 });
    } else {
      translateX.value = withTiming(-DRAWER_WIDTH, {
        duration: 200,
        easing: Easing.in(Easing.cubic),
      });
      backdropOpacity.value = withTiming(0, { duration: 200 });
    }
  }, [visible]);

  const drawerStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: translateX.value }],
  }));

  const backdropStyle = useAnimatedStyle(() => ({
    opacity: backdropOpacity.value,
  }));

  if (!visible && translateX.value <= -DRAWER_WIDTH + 1) {
    // Fully hidden — still render but off-screen for animation
  }

  return (
    <View
      style={[styles.wrapper, !visible && styles.wrapperHidden]}
      pointerEvents={visible ? "auto" : "none"}
    >
      {/* Backdrop */}
      <Pressable style={StyleSheet.absoluteFill} onPress={onClose}>
        <Animated.View style={[styles.backdrop, backdropStyle]} />
      </Pressable>

      {/* Drawer */}
      <Animated.View style={[styles.drawer, drawerStyle]}>
        {/* Logo */}
        <View style={styles.logoContainer}>
          <OnTrackLogo size={22} />
        </View>

        {/* Nav items */}
        <View style={styles.navSection}>
          {NAV_ITEMS.map((item) => {
            const isActive = activeScreen === item.key;
            const isHovered = hoveredKey === item.key && !isActive;
            return (
              <Pressable
                key={item.key}
                style={[
                  styles.navItem,
                  isActive && styles.navItemActive,
                  isHovered && styles.navItemHovered,
                ]}
                onPress={() => {
                  onNavigate(item.key);
                  onClose();
                }}
                onHoverIn={() => setHoveredKey(item.key)}
                onHoverOut={() => setHoveredKey(null)}
              >
                <Feather
                  name={item.icon}
                  size={18}
                  color={isActive ? "#FFFFFF" : colors.boldText}
                  style={styles.navIcon}
                />
                <Text
                  style={[styles.navText, isActive && styles.navTextActive]}
                >
                  {item.label}
                </Text>
              </Pressable>
            );
          })}
        </View>

        {/* Bottom section */}
        <View style={styles.bottomSection}>
          <View style={styles.userRow}>
            <View style={styles.avatar}>
              <Text style={styles.avatarText}>
                {username ? username.charAt(0).toUpperCase() : "?"}
              </Text>
            </View>
            <Text style={styles.username} numberOfLines={1}>
              {username}
            </Text>
          </View>
          <Pressable style={styles.logoutButton} onPress={onLogout}>
            <Feather name="log-out" size={16} color={colors.bodyText} style={{ marginRight: 8 }} />
            <Text style={styles.logoutText}>Logout</Text>
          </Pressable>
        </View>
      </Animated.View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrapper: {
    ...StyleSheet.absoluteFillObject,
    zIndex: 1000,
  },
  wrapperHidden: {
    pointerEvents: "none",
  },
  backdrop: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: "#000",
  },
  drawer: {
    position: "absolute",
    top: 0,
    bottom: 0,
    left: 0,
    width: DRAWER_WIDTH,
    backgroundColor: "#FBF7F9",
    paddingTop: Platform.OS === "ios" ? 60 : 40,
    paddingHorizontal: 0,
    justifyContent: "space-between",
    boxShadow: "2px 0px 12px rgba(0, 0, 0, 0.1)",
    elevation: 8,
  },
  logoContainer: {
    paddingHorizontal: 24,
    paddingBottom: 24,
    borderBottomWidth: 1,
    borderBottomColor: "#EDE7EB",
  },
  navSection: {
    flex: 1,
    paddingTop: 16,
  },
  navItem: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: 12,
    paddingHorizontal: 24,
    marginHorizontal: 12,
    borderRadius: 10,
    marginBottom: 2,
  },
  navItemActive: {
    backgroundColor: colors.primary,
  },
  navItemHovered: {
    backgroundColor: "rgba(242, 185, 74, 0.15)",
  },
  navIcon: {
    marginRight: 12,
  },
  navText: {
    fontSize: 15,
    fontWeight: "500",
    color: colors.boldText,
  },
  navTextActive: {
    color: "#FFFFFF",
    fontWeight: "600",
  },
  bottomSection: {
    borderTopWidth: 1,
    borderTopColor: "#EDE7EB",
    paddingVertical: 16,
    paddingHorizontal: 24,
  },
  userRow: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: 12,
  },
  avatar: {
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: "#7A003C",
    alignItems: "center",
    justifyContent: "center",
    marginRight: 10,
  },
  avatarText: {
    color: "#FFF",
    fontWeight: "700",
    fontSize: 16,
  },
  username: {
    fontSize: 14,
    fontWeight: "600",
    color: "#3B0A1D",
    flex: 1,
  },
  logoutButton: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: 8,
  },
  logoutText: {
    fontSize: 14,
    color: colors.bodyText,
  },
});
