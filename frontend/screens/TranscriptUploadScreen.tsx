import { View, Pressable, StyleSheet, Platform } from "react-native";
import { Text } from "../src/ui/Text";
import * as DocumentPicker from "expo-document-picker";
import { Feather } from "@expo/vector-icons";
import {
  useMutation,
  useMutationState,
  useQueryClient,
  type MutationState,
} from "@tanstack/react-query";
import { colors } from "../src/theme/colors";
import { spacing, radius } from "../src/theme/spacing";
import { getToken } from "../src/utils/tokenStorage";
import { API_BASE_URL } from "../src/config/api";
import { AuthError, isAuthError } from "../src/cache/authedGet";
import { qk } from "../src/cache/keys";
import { toUserMessage } from "../src/utils/errorMessage";
import { fonts, typo } from "../src/theme/typography";

type Props = {
  onBack: () => void;
  onAuthError: () => void;
  onUploadSuccess?: () => void;
};

type GpaResult = {
  gpa: number;
  totalCredits: number;
};

type UploadVariables = {
  file: DocumentPicker.DocumentPickerAsset;
};

const UNREADABLE_MESSAGE =
  "We couldn't read that transcript. Make sure it's your full PDF transcript.";

async function uploadTranscript({ file }: UploadVariables): Promise<GpaResult> {
  const token = await getToken();
  if (!token) throw new AuthError();

  const formData = new FormData();

  //WEB FIX
  if (Platform.OS === "web") {
    // On web, the actual File object exists here
    formData.append("file", file.file as File);
  } else {
    // Native (iOS/Android)
    formData.append("file", {
      uri: file.uri,
      name: file.name,
      type: file.mimeType ?? "application/pdf",
    } as any);
  }

  const res = await fetch(
    `${API_BASE_URL.replace(/\/$/, "")}/api/transcript/calculateGPA`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
      },
      body: formData,
    }
  );

  if (res.status === 401 || res.status === 403) throw new AuthError();
  if (!res.ok) throw new Error(UNREADABLE_MESSAGE);
  return res.json();
}

/**
 * The upload runs as a mutation in the shared query client rather than in this
 * screen's state, so leaving the screen mid-upload neither loses the request
 * nor its result. useMutationState reads it back on the next visit. Cleared on
 * sign-out along with the rest of the cache.
 */
export default function TranscriptUploadScreen({
  onBack,
  onAuthError,
  onUploadSuccess,
}: Props) {
  const queryClient = useQueryClient();

  const { mutate } = useMutation({
    mutationKey: qk.transcriptGpa,
    mutationFn: uploadTranscript,
    // Kept until replaced or signed out, not dropped five minutes after the screen unmounts.
    gcTime: Infinity,
    // Hook-level callbacks, unlike ones passed to mutate(), still fire after an unmount.
    onSuccess: () => onUploadSuccess?.(),
    onError: (err) => {
      if (isAuthError(err)) onAuthError();
    },
  });

  const states = useMutationState<MutationState<GpaResult, Error, UploadVariables>>({
    filters: { mutationKey: qk.transcriptGpa },
    select: (m) => m.state as MutationState<GpaResult, Error, UploadVariables>,
  });
  const latest = states[states.length - 1];

  const uploading = latest?.status === "pending";
  const fileName = latest?.variables?.file.name ?? null;
  const result = latest?.status === "success" ? latest.data ?? null : null;
  const failure = latest?.status === "error" ? latest.error : null;
  // Auth failures are handed to onAuthError instead of shown here.
  const error =
    failure && !isAuthError(failure) ? toUserMessage(failure, UNREADABLE_MESSAGE) : null;

  const pickAndUpload = async () => {
    const token = await getToken();
    if (!token) {
      onAuthError();
      return;
    }

    const picked = await DocumentPicker.getDocumentAsync({
      type: ["application/pdf", "image/*"],
      copyToCacheDirectory: true,
    });

    if (picked.canceled) return;

    // Only the newest upload matters. Drop older ones so they do not pile up.
    const cache = queryClient.getMutationCache();
    cache.findAll({ mutationKey: qk.transcriptGpa }).forEach((m) => cache.remove(m));

    mutate({ file: picked.assets[0] });
  };


  return (
    <View style={styles.container}>

      {/* Upload Card */}
      <View style={styles.card}>
        <Text style={styles.cardTitle}>Upload Transcript</Text>
        <Text style={styles.cardSub}>
          We read your courses and grades straight from the PDF.
        </Text>

        <View style={styles.uploadBox}>
          <View style={styles.iconCircle}>
            <Feather name="upload" size={24} color={colors.primary} />
          </View>

          <Text style={styles.uploadSub}>
            Supported formats: PDF, JPG, PNG
          </Text>

          <Pressable
            style={styles.browseButton}
            onPress={pickAndUpload}
            disabled={uploading}
          >
            <Feather
              name="file"
              size={16}
              color={colors.onField}
              style={{ marginRight: 6 }}
            />
            <Text style={styles.browseText}>
              {uploading ? "Uploading..." : "Browse Files"}
            </Text>
          </Pressable>

          {fileName && (
            <Text style={styles.fileName}>{fileName}</Text>
          )}
        </View>
      </View>

      {error && <Text style={styles.error}>{error}</Text>}

      {/* Result Card */}
      {result && (
        <View style={styles.resultCard}>
          {result.totalCredits > 0 ? (
            <>
              <Text style={styles.resultTitle}>Predicted GPA</Text>
              <Text style={styles.gpa}>{result.gpa.toFixed(2)}</Text>
              <Text style={styles.resultSub}>
                {result.totalCredits} credits
              </Text>
            </>
          ) : (
            <>
              <Text style={styles.resultTitle}>You're all set</Text>
              <Text style={styles.resultSub}>
                No completed courses yet. We've added your current term so you can
                start tracking. Your GPA builds from here.
              </Text>
            </>
          )}
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    padding: spacing.lg,
    backgroundColor: colors.background,
    flex: 1,
    maxWidth: 540,
    width: "100%",
    alignSelf: "center",
  },
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: spacing.lg,
    borderWidth: 1,
    borderColor: colors.borderSubtle,
  },
  cardTitle: {
    ...typo.h2,
    color: colors.boldText,
    marginBottom: 6,
  },
  cardSub: {
    ...typo.body,
    color: colors.bodyText,
    marginBottom: spacing.lg,
  },
  uploadBox: {
    borderWidth: 1,
    borderStyle: "dashed",
    borderColor: colors.border,
    borderRadius: radius.lg,
    padding: spacing.lg,
    alignItems: "center",
  },
  iconCircle: {
    backgroundColor: colors.primarySoft,
    borderRadius: 999,
    padding: spacing.md,
    marginBottom: spacing.md,
    alignItems: "center",
    justifyContent: "center",
  },
  uploadTitle: {
    ...typo.h3,
    color: colors.boldText,
    marginBottom: 4,
  },
  uploadSub: {
    ...typo.body,
    color: colors.bodyText,
    textAlign: "center",
    marginBottom: spacing.md,
  },
  browseButton: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: colors.primary,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.md,
  },
  browseText: {
    ...typo.label,
    color: colors.onField,
    fontFamily: fonts.display,
  },
  fileName: {
    ...typo.caption,
    marginTop: spacing.sm,
    color: colors.bodyText,
  },
  resultCard: {
    backgroundColor: colors.primary,
    padding: spacing.lg,
    borderRadius: radius.lg,
    alignItems: "center",
    marginTop: spacing.lg,
  },
  resultTitle: {
    ...typo.label,
    color: colors.onFieldMuted,
    marginBottom: 8,
  },
  gpa: {
    ...typo.display,
    color: colors.onField,
    fontSize: 48,
    lineHeight: 52,
    fontVariant: ["tabular-nums"],
  },
  resultSub: {
    ...typo.caption,
    color: colors.onFieldMuted,
    marginTop: 6,
    fontVariant: ["tabular-nums"],
  },
  error: {
    ...typo.body,
    color: colors.danger,
    marginTop: spacing.md,
    textAlign: "center",
  },
});
