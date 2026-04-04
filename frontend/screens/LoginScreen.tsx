import {
  View,
  Text,
  TextInput,
  Pressable,
  StyleSheet,
  KeyboardAvoidingView,
  Platform,
} from "react-native";
import { useRef, useState } from "react";
import { Feather } from "@expo/vector-icons";
import { colors } from "../src/theme/colors";
import { spacing } from "../src/theme/spacing";
import { setToken } from "../src/utils/tokenStorage";
import { API_BASE_URL } from "../src/config/api";
import OnTrackLogo from "./components/OnTrackLogo";

const VERIFY_RESEND_URL = `${API_BASE_URL.replace(/\/$/, "")}/verify/resend`;

type Props = {
  onLoginSuccess: () => void;
  onGoToRegister: () => void;
  onGoToForgotPassword: () => void;
};

export default function LoginScreen({ onLoginSuccess, onGoToRegister, onGoToForgotPassword }: Props) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [focusedField, setFocusedField] = useState<string | null>(null);
  const [showPassword, setShowPassword] = useState(false);
  const [needsVerification, setNeedsVerification] = useState(false);
  const [resending, setResending] = useState(false);
  const [resendMessage, setResendMessage] = useState<string | null>(null);
  const passwordRef = useRef<TextInput>(null);

  const login = async () => {
    if (!email || !password) {
      setError("Email and password are required");
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const res = await fetch(
        `${API_BASE_URL.replace(/\/$/, "")}/login`,
        {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password }),
        }
      );

      if (res.status === 403) {
        setNeedsVerification(true);
        return;
      }

      if (res.status === 401) {
        throw new Error("Invalid credentials");
      }

      if (!res.ok) {
        throw new Error("Login failed");
      }

      // Backend returns RAW JWT STRING
      const token = (await res.text()).trim();

      if (!token) {
        throw new Error("Empty token");
      }

      await setToken(token);
      onLoginSuccess();
    } catch (err) {
      setError("Invalid email or password");
    } finally {
      setLoading(false);
    }
  };

  const resendVerification = async () => {
    setResending(true);
    setResendMessage(null);
    try {
      const res = await fetch(VERIFY_RESEND_URL, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email }),
      });
      if (res.ok) {
        setResendMessage("Verification email sent");
      } else {
        const text = await res.text();
        setResendMessage(text || "Failed to resend. Please try again.");
      }
    } catch {
      setResendMessage("Could not connect to server.");
    } finally {
      setResending(false);
    }
  };

  const hasError = !!error;

  if (needsVerification) {
    return (
      <KeyboardAvoidingView
        style={styles.screen}
        behavior={Platform.OS === "ios" ? "padding" : undefined}
      >
        <View style={styles.card}>
          <View style={styles.logoWrapper}>
            <OnTrackLogo size={36} />
          </View>
          <Text style={styles.verifyTitle}>Verify your email</Text>
          <Text style={styles.verifyBody}>
            Your email{" "}
            <Text style={styles.verifyEmail}>{email}</Text>{" "}
            has not been verified yet. Please check your inbox for a
            verification link or resend it below.
          </Text>
          <Pressable
            style={({ pressed }) => [
              styles.button,
              resending && styles.buttonDisabled,
              !resending && pressed && styles.buttonPressed,
            ]}
            onPress={resendVerification}
            disabled={resending}
            accessibilityRole="button"
            accessibilityLabel={resending ? "Resending email" : "Resend verification email"}
          >
            <Text style={styles.buttonText}>
              {resending ? "Sending..." : "Resend verification email"}
            </Text>
          </Pressable>

          {resendMessage && (
            <Text style={styles.resendMessage}>{resendMessage}</Text>
          )}

          <Pressable
            onPress={() => {
              setNeedsVerification(false);
              setResendMessage(null);
            }}
            style={styles.footerLinks}
          >
            <Text style={styles.linkText}>
              Back to <Text style={styles.linkBold}>Log in</Text>
            </Text>
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
        <Text style={styles.subtitle}>Sign in to continue</Text>

        <TextInput
          placeholder="Email"
          placeholderTextColor={colors.bodyText}
          value={email}
          onChangeText={setEmail}
          autoCapitalize="none"
          keyboardType="email-address"
          returnKeyType="next"
          onSubmitEditing={() => passwordRef.current?.focus()}
          onFocus={() => setFocusedField("email")}
          onBlur={() => setFocusedField(null)}
          style={[
            styles.input,
            focusedField === "email" && styles.inputFocused,
            hasError && styles.inputError,
          ]}
          accessibilityLabel="Email address"
        />

        <View
          style={[
            styles.passwordRow,
            focusedField === "password" && styles.inputFocused,
            hasError && styles.inputError,
          ]}
        >
          <TextInput
            ref={passwordRef}
            placeholder="Password"
            placeholderTextColor={colors.bodyText}
            value={password}
            onChangeText={setPassword}
            secureTextEntry={!showPassword}
            returnKeyType="go"
            onSubmitEditing={login}
            onFocus={() => setFocusedField("password")}
            onBlur={() => setFocusedField(null)}
            style={styles.passwordInput}
            accessibilityLabel="Password"
          />
          <Pressable
            onPress={() => setShowPassword((v) => !v)}
            style={styles.eyeButton}
            accessibilityLabel={showPassword ? "Hide password" : "Show password"}
            accessibilityRole="button"
            hitSlop={8}
          >
            <Feather
              name={showPassword ? "eye-off" : "eye"}
              size={20}
              color={colors.bodyText}
            />
          </Pressable>
        </View>

        {hasError && <Text style={styles.error}>{error}</Text>}

        <Pressable
          style={({ pressed }) => [
            styles.button,
            loading && styles.buttonDisabled,
            !loading && pressed && styles.buttonPressed,
          ]}
          onPress={login}
          disabled={loading}
          accessibilityRole="button"
          accessibilityLabel={loading ? "Signing in" : "Log in"}
          accessibilityState={{ disabled: loading, busy: loading }}
        >
          <Text style={styles.buttonText}>
            {loading ? "Signing in..." : "Log in"}
          </Text>
        </Pressable>

        <View style={styles.footerLinks}>
          <Pressable onPress={onGoToRegister}>
            <Text style={styles.linkText}>
              Don't have an account?{" "}
              <Text style={styles.linkBold}>Sign up</Text>
            </Text>
          </Pressable>

          <Pressable onPress={onGoToForgotPassword}>
            <Text style={styles.linkText}>
              <Text style={styles.linkBold}>Forgot password?</Text>
            </Text>
          </Pressable>
        </View>
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
  passwordRow: {
    flexDirection: "row",
    alignItems: "center",
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 12,
    marginBottom: spacing.md,
    backgroundColor: colors.surface,
  },
  passwordInput: {
    flex: 1,
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 16,
    color: colors.boldText,
  },
  eyeButton: {
    paddingHorizontal: 14,
    paddingVertical: 12,
    justifyContent: "center",
    alignItems: "center",
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
  footerLinks: {
    marginTop: spacing.md,
    alignItems: "center",
    gap: spacing.sm,
  },
  verifyTitle: {
    fontSize: 20,
    fontWeight: "700",
    color: colors.boldText,
    textAlign: "center",
    marginBottom: spacing.sm,
  },
  verifyBody: {
    fontSize: 14,
    color: colors.bodyText,
    textAlign: "center",
    lineHeight: 20,
    marginBottom: spacing.lg,
  },
  verifyEmail: {
    fontWeight: "600",
    color: colors.boldText,
  },
  resendMessage: {
    fontSize: 13,
    color: colors.bodyText,
    textAlign: "center",
    marginTop: spacing.sm,
  },
  linkText: {
    fontSize: 14,
    color: colors.bodyText,
  },
  linkBold: {
    color: colors.primary,
    fontWeight: "600",
  },
});
