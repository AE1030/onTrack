import { View, StyleSheet } from 'react-native';
import { Text } from '../src/ui/Text';
import { colors } from '../src/theme/colors';
import { typo } from '../src/theme/typography';

export default function NotFoundScreen() {
  return (
    <View style={styles.container}>
      <Text style={styles.code}>404</Text>
      <Text style={styles.message}>This page could not be found</Text>
    </View>
  );
}

const styles = StyleSheet.create({
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
