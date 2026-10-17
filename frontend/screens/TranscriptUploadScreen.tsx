import { View, Text, Pressable, StyleSheet, Platform } from "react-native";
import * as DocumentPicker from "expo-document-picker";
import { Feather } from "@expo/vector-icons";
import {
  useMutation,
  useMutationState,
  useQueryClient,
  type MutationState,
} from "@tanstack/react-query";
import { colors } from "../src/theme/colors";
import { spacing } from "../src/theme/spacing";
import { getToken } from "../src/utils/tokenStorage";
import { API_BASE_URL } from "../src/config/api";
import { AuthError, isAuthError } from "../src/cache/authedGet";
import { qk } from "../src/cache/keys";

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
    failure && !isAuthError(failure) ? (failure as Error).message || UNREADABLE_MESSAGE : null;

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
          Upload your PDF transcript to automatically extract courses and grades.
        </Text>

        <View style={styles.uploadBox}>
          <View style={styles.iconCircle}>
            <Feather name="upload" size={24} color={colors.primary} />
          </View>

          <Text style={styles.uploadTitle}>
            Select your transcript file
          </Text>

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
              color="white"
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
                Total Credits: {result.totalCredits}
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
    backgroundColor: "white",
    borderRadius: 16,
    padding: spacing.lg,
    boxShadow: "0px 2px 6px rgba(0, 0, 0, 0.06)",
    elevation: 2,
  },
  cardTitle: {
    fontSize: 17,
    fontWeight: "600",
    color: colors.boldText,
    marginBottom: 6,
  },
  cardSub: {
    color: colors.bodyText,
    fontSize: 14,
    lineHeight: 20,
    marginBottom: spacing.lg,
  },
  uploadBox: {
    borderWidth: 1,
    borderStyle: "dashed",
    borderColor: colors.border,
    borderRadius: 14,
    padding: spacing.lg,
    alignItems: "center",
  },
  iconCircle: {
    backgroundColor: "#F2E6EC",
    borderRadius: 999,
    padding: spacing.md,
    marginBottom: spacing.md,
    alignItems: "center",
    justifyContent: "center",
  },
  uploadTitle: {
    fontWeight: "600",
    fontSize: 15,
    color: colors.boldText,
    marginBottom: 4,
  },
  uploadSub: {
    color: colors.bodyText,
    fontSize: 13,
    textAlign: "center",
    marginBottom: spacing.md,
  },
  browseButton: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: colors.primary,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.lg,
    borderRadius: 10,
  },
  browseText: {
    color: "white",
    fontWeight: "500",
    fontSize: 14,
  },
  fileName: {
    marginTop: spacing.sm,
    color: colors.bodyText,
    fontSize: 12,
  },
  resultCard: {
    backgroundColor: colors.primary,
    padding: spacing.lg,
    borderRadius: 16,
    alignItems: "center",
    marginTop: spacing.lg,
    boxShadow: "0px 4px 12px rgba(0, 0, 0, 0.15)",
    elevation: 4,
  },
  resultTitle: {
    color: "rgba(255,255,255,0.85)",
    fontWeight: "500",
    fontSize: 14,
    marginBottom: 8,
  },
  gpa: {
    color: "white",
    fontSize: 42,
    fontWeight: "700",
  },
  resultSub: {
    color: "rgba(255,255,255,0.75)",
    fontSize: 13,
    marginTop: 6,
  },
  error: {
    color: "#DC2626",
    fontSize: 13,
    marginTop: spacing.md,
    textAlign: "center",
  },
});
