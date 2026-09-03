export type HouseholdRole = 'OWNER' | 'ADMIN' | 'MEMBER';

export interface ApiFailure {
  code: string;
  message: string;
  fields?: Record<string, string>;
}

export interface ApiEnvelope<T> {
  data?: T;
  error?: ApiFailure;
}

export interface CsrfToken {
  headerName: string;
  parameterName: string;
  token: string;
}

export interface Session {
  userId: number;
  householdId: number;
  email: string;
  displayName: string;
  role: HouseholdRole;
  username: string;
}

export interface RegisterRequest {
  email: string;
  displayName: string;
  password: string;
  mode: 'CREATE' | 'JOIN';
  householdName: string | null;
  inviteToken: string | null;
}

export interface RegisterResponse {
  email: string;
  displayName: string;
  householdName: string;
  role: HouseholdRole;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}
