import {
  View,
  Text,
  TextInput,
  Pressable,
  StyleSheet,
  KeyboardAvoidingView,
  Platform,
} from "react-native";
import { useState } from "react";
import { colors } from "../src/theme/colors";
import { spacing } from "../src/theme/spacing";
import { API_BASE_URL } from "../src/config/api";
import OnTrackLogo from "./components/OnTrackLogo";

type Props = {
  onGoToLogin: () => void;
};

export default function ForgotPasswordScreen({ onGoToLogin }: Props) {
  const [email, setEmail] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);
  const [focusedField, setFocusedField] = useState<string | null>(null);

  const handleSubmit = async () => {
    if (!email.trim()) {
      setError("Email is required");
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const res = await fetch(
        `${API_BASE_URL.replace(/\/$/, "")}/forgot-password`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ email: email.trim() }),
        }
      );

      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || "Something went wrong");
      }

      setSent(true);
    } catch (err: any) {
      setError(err.message || "Could not connect to server.");
    } finally {
      setLoading(false);
    }
  };

  if (sent) {
    return (
      <KeyboardAvoidingView
        style={styles.screen}
        behavior={Platform.OS === "ios" ? "padding" : undefined}
      >
        <View style={styles.card}>
          <View style={styles.logoWrapper}>
            <OnTrackLogo size={36} />
          </View>
          <Text style={styles.successTitle}>Check your email</Text>
          <Text style={styles.successBody}>
            If an account exists for{" "}
            <Text style={styles.successEmail}>{email}</Text>, we sent a password
            reset link. Tap the link in the email to set a new password.
          </Text>
          <Pressable
            style={({ pressed }) => [
              styles.button,
              pressed && styles.buttonPressed,
            ]}
            onPress={onGoToLogin}
            accessibilityRole="button"
            accessibilityLabel="Back to login"
          >
            <Text style={styles.buttonText}>Back to Log in</Text>
          </Pressable>
        </View>
      </KeyboardAvoidingView>
    );
  }

  return (
    <KeyboardAvoidingView
      style={styles.screen}
      behavior={Platform.OS === "ios" ? "padding" : undefined}
    >
      <View style={styles.card}>
        <View style={styles.logoWrapper}>
          <OnTrackLogo size={36} />
        </View>
        <Text style={styles.subtitle}>
          Enter your email and we'll send you a link to reset your password.
        </Text>

        <TextInput
          placeholder="Email"
          placeholderTextColor={colors.bodyText}
          value={email}
          onChangeText={setEmail}
          autoCapitalize="none"
          keyboardType="email-address"
          returnKeyType="go"
          onSubmitEditing={handleSubmit}
          onFocus={() => setFocusedField("email")}
          onBlur={() => setFocusedField(null)}
          style={[
            styles.input,
            focusedField === "email" && styles.inputFocused,
            !!error && styles.inputError,
          ]}
          accessibilityLabel="Email address"
        />

        {error && <Text style={styles.error}>{error}</Text>}

        <Pressable
          style={({ pressed }) => [
            styles.button,
            loading && styles.buttonDisabled,
            !loading && pressed && styles.buttonPressed,
          ]}
          onPress={handleSubmit}
          disabled={loading}
          accessibilityRole="button"
          accessibilityLabel={loading ? "Sending" : "Send reset link"}
          accessibilityState={{ disabled: loading, busy: loading }}
        >
          <Text style={styles.buttonText}>
            {loading ? "Sending..." : "Send reset link"}
          </Text>
        </Pressable>

        <Pressable onPress={onGoToLogin} style={styles.linkWrapper}>
          <Text style={styles.linkText}>
            Back to <Text style={styles.linkBold}>Log in</Text>
          </Text>
        </Pressable>
      </View>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    justifyContent: "center",
    padding: spacing.lg,
    backgroundColor: colors.background,
  },
  card: {
    backgroundColor: colors.surface,
    borderRadius: 16,
    padding: spacing.lg,
    maxWidth: 400,
    width: "100%",
    alignSelf: "center",
    boxShadow: "0px 2px 12px rgba(59, 10, 29, 0.08)",
    elevation: 3,
  },
  logoWrapper: {
    alignItems: "center",
    marginBottom: spacing.md,
  },
  subtitle: {
    textAlign: "center",
    fontSize: 14,
    color: colors.bodyText,
    marginBottom: spacing.lg,
  },
  input: {
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 12,
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 16,
    color: colors.boldText,
    marginBottom: spacing.md,
    backgroundColor: colors.surface,
  },
  inputFocused: {
    borderColor: colors.primary,
    borderWidth: 1.5,
  },
  inputError: {
    borderColor: "#D32F2F",
  },
  button: {
    backgroundColor: colors.primary,
    paddingVertical: 14,
    borderRadius: 12,
    alignItems: "center",
    marginTop: spacing.sm,
    minHeight: 48,
    justifyContent: "center",
  },
  buttonPressed: {
    opacity: 0.85,
  },
  buttonDisabled: {
    opacity: 0.5,
  },
  buttonText: {
    color: "#FFFFFF",
    fontWeight: "600",
    fontSize: 16,
  },
  error: {
    color: "#D32F2F",
    fontSize: 13,
    marginBottom: spacing.sm,
    textAlign: "center",
  },
  linkWrapper: {
    marginTop: spacing.md,
    alignItems: "center",
  },
  linkText: {
    fontSize: 14,
    color: colors.bodyText,
  },
  linkBold: {
    color: colors.primary,
    fontWeight: "600",
  },
  successTitle: {
    fontSize: 20,
    fontWeight: "700",
    color: colors.boldText,
    textAlign: "center",
    marginBottom: spacing.sm,
  },
  successBody: {
    fontSize: 14,
    color: colors.bodyText,
    textAlign: "center",
    lineHeight: 20,
    marginBottom: spacing.lg,
  },
  successEmail: {
    fontWeight: "600",
    color: colors.boldText,
  },
});
