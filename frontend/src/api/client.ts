// src/api/client.ts

const API_BASE = "http://localhost:8080"; // change later if needed

type Json = Record<string, unknown>;

async function request<T>(
  path: string,
  method: "GET" | "POST",
  body?: Json
): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers: {
      "Content-Type": "application/json",
    },
    body: body ? JSON.stringify(body) : undefined,
  });

  const text = await res.text();
  const data = text ? JSON.parse(text) : null;

  if (!res.ok) {
    throw new Error(data?.message ?? "Request failed");
  }

  return data as T;
}

/* ===== Auth ===== */

export type AuthResponse = {
  token: string;
};

export function login(email: string, password: string) {
  return request<AuthResponse>("/login", "POST", {
    email,
    password,
  });
}

export function register(username: string, email: string, password: string) {
  return request<AuthResponse>("/register", "POST", {
    username,
    email,
    password,
  });
}
