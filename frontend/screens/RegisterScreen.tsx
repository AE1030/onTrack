import {
  View,
  Text,
  TextInput,
  Pressable,
  StyleSheet,
  KeyboardAvoidingView,
  Platform,
  ScrollView,
} from "react-native";
import { useRef, useState } from "react";
import { Feather } from "@expo/vector-icons";
import { colors } from "../src/theme/colors";
import { spacing } from "../src/theme/spacing";
import { API_BASE_URL } from "../src/config/api";
import OnTrackLogo from "./components/OnTrackLogo";

type Props = {
  onGoToLogin: () => void;
};

const MCMASTER_REGEX = /@mcmaster\.ca$/i;
const PASSWORD_REGEX = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$/;

export default function RegisterScreen({ onGoToLogin }: Props) {
  const [email, setEmail] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [emailError, setEmailError] = useState<string | null>(null);
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);
  const [confirmPassword, setConfirmPassword] = useState("");
  const [confirmError, setConfirmError] = useState<string | null>(null);
  const [focusedField, setFocusedField] = useState<string | null>(null);
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirm, setShowConfirm] = useState(false);
  const [resending, setResending] = useState(false);
  const [resendMessage, setResendMessage] = useState<string | null>(null);

  const usernameRef = useRef<TextInput>(null);
  const passwordRef = useRef<TextInput>(null);
  const confirmRef = useRef<TextInput>(null);

  const validateEmail = (value: string) => {
    setEmail(value);
    if (value.length > 0 && !MCMASTER_REGEX.test(value)) {
      setEmailError("Please use a valid McMaster email ending with @mcmaster.ca");
    } else {
      setEmailError(null);
    }
  };

  const validatePassword = (value: string) => {
    setPassword(value);
    if (value.length > 0 && !PASSWORD_REGEX.test(value)) {
      setPasswordError(
        "Password must be at least 8 characters with one uppercase, one lowercase, and one number"
      );
    } else {
      setPasswordError(null);
    }
    if (confirmPassword.length > 0 && value !== confirmPassword) {
      setConfirmError("Passwords do not match");
    } else {
      setConfirmError(null);
    }
  };

  const validateConfirm = (value: string) => {
    setConfirmPassword(value);
    if (value.length > 0 && value !== password) {
      setConfirmError("Passwords do not match");
    } else {
      setConfirmError(null);
    }
  };

  const register = async () => {
    setError(null);

    if (!email || !username || !password || !confirmPassword) {
      setError("All fields are required");
      return;
    }

    if (password !== confirmPassword) {
      setConfirmError("Passwords do not match");
      return;
    }

    if (!MCMASTER_REGEX.test(email)) {
      setEmailError("Please use a valid McMaster email ending with @mcmaster.ca");
      return;
    }

    if (!PASSWORD_REGEX.test(password)) {
      setPasswordError(
        "Password must be at least 8 characters with one uppercase, one lowercase, and one number"
      );
      return;
    }

    setLoading(true);

    try {
      const res = await fetch(`${API_BASE_URL.replace(/\/$/, "")}/register`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, username, password }),
      });

      if (res.status === 400 || res.status === 409) {
        const data = await res.text();
        throw new Error(data || "Registration failed");
      }

      if (!res.ok) {
        throw new Error("Registration failed");
      }

      setSuccess(true);
    } catch (err: any) {
      setError(err.message || "Something went wrong");
    } finally {
      setLoading(false);
    }
  };

  const resendVerification = async () => {
    setResending(true);
    setResendMessage(null);
    try {
      const res = await fetch(
        `${API_BASE_URL.replace(/\/$/, "")}/verify/resend`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ email }),
        }
      );
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

  if (success) {
    return (
      <KeyboardAvoidingView
        style={styles.screen}
        behavior={Platform.OS === "ios" ? "padding" : undefined}
      >
        <View style={styles.card}>
          <View style={styles.logoWrapper}>
            <OnTrackLogo size={36} />
          </View>
          <Text style={styles.successTitle}>Check your email for Verification</Text>
          <Text style={styles.successBody}>
            We sent a verification link to{" "}
            <Text style={styles.successEmail}>{email}</Text>. Please verify your
            email to activate your account.
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

          <Pressable onPress={onGoToLogin} style={styles.linkWrapper}>
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
      <ScrollView
        contentContainerStyle={styles.scrollContent}
        keyboardShouldPersistTaps="handled"
      >
        <View style={styles.card}>
          <View style={styles.logoWrapper}>
            <OnTrackLogo size={36} />
          </View>
          <Text style={styles.subtitle}>Create your account</Text>

          <TextInput
            placeholder="McMaster email"
            placeholderTextColor={colors.bodyText}
            value={email}
            onChangeText={validateEmail}
            autoCapitalize="none"
            keyboardType="email-address"
            returnKeyType="next"
            onSubmitEditing={() => usernameRef.current?.focus()}
            onFocus={() => setFocusedField("email")}
            onBlur={() => setFocusedField(null)}
            style={[
              styles.input,
              focusedField === "email" && styles.inputFocused,
              (hasError || emailError) && styles.inputError,
            ]}
            accessibilityLabel="McMaster email address"
          />
          {emailError && <Text style={styles.fieldError}>{emailError}</Text>}

          <TextInput
            ref={usernameRef}
            placeholder="Username"
            placeholderTextColor={colors.bodyText}
            value={username}
            onChangeText={setUsername}
            autoCapitalize="none"
            returnKeyType="next"
            onSubmitEditing={() => passwordRef.current?.focus()}
            onFocus={() => setFocusedField("username")}
            onBlur={() => setFocusedField(null)}
            style={[
              styles.input,
              focusedField === "username" && styles.inputFocused,
              hasError && styles.inputError,
            ]}
            accessibilityLabel="Username"
          />

          <View
            style={[
              styles.passwordRow,
              focusedField === "password" && styles.inputFocused,
              (hasError || passwordError) && styles.inputError,
            ]}
          >
            <TextInput
              ref={passwordRef}
              placeholder="Password"
              placeholderTextColor={colors.bodyText}
              value={password}
              onChangeText={validatePassword}
              secureTextEntry={!showPassword}
              returnKeyType="next"
              onSubmitEditing={() => confirmRef.current?.focus()}
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
          {passwordError && <Text style={styles.fieldError}>{passwordError}</Text>}

          <View
            style={[
              styles.passwordRow,
              focusedField === "confirm" && styles.inputFocused,
              (hasError || confirmError) && styles.inputError,
            ]}
          >
            <TextInput
              ref={confirmRef}
              placeholder="Confirm password"
              placeholderTextColor={colors.bodyText}
              value={confirmPassword}
              onChangeText={validateConfirm}
              secureTextEntry={!showConfirm}
              returnKeyType="go"
              onSubmitEditing={register}
              onFocus={() => setFocusedField("confirm")}
              onBlur={() => setFocusedField(null)}
              style={styles.passwordInput}
              accessibilityLabel="Confirm password"
            />
            <Pressable
              onPress={() => setShowConfirm((v) => !v)}
              style={styles.eyeButton}
              accessibilityLabel={showConfirm ? "Hide confirm password" : "Show confirm password"}
              accessibilityRole="button"
              hitSlop={8}
            >
              <Feather
                name={showConfirm ? "eye-off" : "eye"}
                size={20}
                color={colors.bodyText}
              />
            </Pressable>
          </View>
          {confirmError && <Text style={styles.fieldError}>{confirmError}</Text>}

          {hasError && <Text style={styles.error}>{error}</Text>}

          <Pressable
            style={({ pressed }) => [
              styles.button,
              loading && styles.buttonDisabled,
              !loading && pressed && styles.buttonPressed,
            ]}
            onPress={register}
            disabled={loading}
            accessibilityRole="button"
            accessibilityLabel={loading ? "Creating account" : "Sign up"}
            accessibilityState={{ disabled: loading, busy: loading }}
          >
            <Text style={styles.buttonText}>
              {loading ? "Creating account..." : "Sign up"}
            </Text>
          </Pressable>

          <Pressable onPress={onGoToLogin} style={styles.linkWrapper}>
            <Text style={styles.linkText}>
              Already have an account?{" "}
              <Text style={styles.linkBold}>Log in</Text>
            </Text>
          </Pressable>
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    justifyContent: "center",
    backgroundColor: colors.background,
  },
  scrollContent: {
    flexGrow: 1,
    justifyContent: "center",
    padding: spacing.lg,
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
  fieldError: {
    color: "#D32F2F",
    fontSize: 12,
    marginTop: -spacing.sm,
    marginBottom: spacing.md,
    paddingHorizontal: 4,
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
  resendMessage: {
    fontSize: 13,
    color: colors.bodyText,
    textAlign: "center",
    marginTop: spacing.sm,
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
