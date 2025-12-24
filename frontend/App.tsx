import { useState } from "react";
import { StyleSheet, View, Text } from "react-native";

import LoginScreen from "./src/screens/LoginScreen";
import RegisterScreen from "./src/screens/RegisterScreen";

type Screen = "login" | "register" | "home";

export default function App() {
  const [screen, setScreen] = useState<Screen>("login");

  if (screen === "login") {
    return (
      <LoginScreen
        onGoRegister={() => setScreen("register")}
        onSuccess={() => setScreen("home")}
      />
    );
  }

  if (screen === "register") {
    return (
      <RegisterScreen
        onGoLogin={() => setScreen("login")}
        onSuccess={() => setScreen("home")}
      />
    );
  }

  // Home screen (placeholder)
  return (
    <View style={styles.container}>
      <Text style={styles.title}>You’re logged in ✅</Text>
      <Text>This is the home screen.</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    gap: 12,
  },
  title: {
    fontSize: 24,
    fontWeight: "700",
  },
});
