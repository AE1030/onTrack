import { useMemo } from "react";
import { Modal, Pressable, StyleSheet, View } from "react-native";
import { Feather } from "@expo/vector-icons";
import { Text } from "../../src/ui/Text";
import { noHover } from "../../src/ui/hover";
import { Palette } from "../../src/theme/colors";
import { useTheme } from "../../src/theme/ThemeContext";
import { radius, spacing } from "../../src/theme/spacing";
import { fonts, typo } from "../../src/theme/typography";

type Props = {
  visible: boolean;
  title: string;
  message: string;
  onClose: () => void;
};

/**
 * A single-message notice for a feature that is temporarily unavailable. Same shell as the
 * calendar's other modals (scrim, surface card, radius.lg), with one calm icon and one action.
 */
export default function ServiceNoticeModal({ visible, title, message, onClose }: Props) {
  const colors = useTheme();
  const styles = useMemo(() => makeStyles(colors), [colors]);

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <Pressable {...noHover} style={styles.overlay} onPress={onClose}>
        <Pressable
          {...noHover}
          style={styles.content}
          onPress={() => {}}
          accessibilityRole="alert"
        >
          <View style={styles.iconWrap}>
            <Feather name="clock" size={22} color={colors.primaryInk} />
          </View>
          <Text style={styles.title}>{title}</Text>
          <Text style={styles.message}>{message}</Text>
          <Pressable
            style={styles.button}
            onPress={onClose}
            accessibilityRole="button"
          >
            <Text style={styles.buttonText}>Got it</Text>
          </Pressable>
        </Pressable>
      </Pressable>
    </Modal>
  );
}

const makeStyles = (colors: Palette) =>
  StyleSheet.create({
    overlay: {
      flex: 1,
      backgroundColor: colors.scrim,
      justifyContent: "center",
      alignItems: "center",
    },
    content: {
      backgroundColor: colors.surface,
      borderRadius: radius.lg,
      padding: spacing.lg,
      width: "85%",
      maxWidth: 400,
    },
    iconWrap: {
      width: 44,
      height: 44,
      borderRadius: radius.lg,
      backgroundColor: colors.primarySoft,
      alignItems: "center",
      justifyContent: "center",
      marginBottom: spacing.md,
    },
    title: {
      ...typo.h2,
      color: colors.boldText,
      marginBottom: spacing.sm,
    },
    message: {
      ...typo.body,
      color: colors.bodyText,
      marginBottom: spacing.lg,
    },
    button: {
      alignSelf: "flex-end",
      backgroundColor: colors.primary,
      paddingVertical: 10,
      paddingHorizontal: 20,
      borderRadius: radius.md,
    },
    buttonText: {
      ...typo.label,
      color: colors.onPrimary,
      fontFamily: fonts.display,
    },
  });
