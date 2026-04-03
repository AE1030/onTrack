import { Text, View, StyleSheet } from 'react-native';

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
    backgroundColor: '#F5F1F3',
  },
  code: {
    fontSize: 48,
    fontWeight: '700',
    color: '#3B0A1D',
  },
  message: {
    fontSize: 16,
    color: '#888',
    marginTop: 8,
  },
});
