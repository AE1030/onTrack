import { useMemo } from "react";
import { View, StyleSheet } from 'react-native';
import { Text } from '../src/ui/Text';
import { Palette } from '../src/theme/colors';
import { useTheme } from '../src/theme/ThemeContext';
import { typo } from '../src/theme/typography';

export default function NotFoundScreen() {
  const colors = useTheme();
  const styles = useMemo(() => makeStyles(colors), [colors]);
  return (
    <View style={styles.container}>
      <Text style={styles.code}>404</Text>
      <Text style={styles.message}>This page could not be found</Text>
    </View>
  );
}

const makeStyles = (colors: Palette) =>
  StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.background,
  },
  code: {
    ...typo.display,
    fontSize: 48,
    lineHeight: 52,
    color: colors.boldText,
  },
  message: {
    ...typo.bodyLarge,
    color: colors.bodyText,
    marginTop: 8,
  },
});
