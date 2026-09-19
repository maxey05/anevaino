import { lazy, Suspense } from "react";
import { Route, Routes } from "react-router";

const LandingPage = lazy(() => import("../features/auth/LandingPage")); // RFC-002
const UploadPage = lazy(() => import("../features/upload/UploadPage")); // RFC-009
const ReviewPage = lazy(() => import("../features/review/ReviewPage")); // RFC-009
const MapPage = lazy(() => import("../features/map/MapPage")); // RFC-011
const SettingsPage = lazy(() => import("../features/settings/SettingsPage")); // RFC-011
const PrivacyPage = lazy(() => import("../features/settings/PrivacyPage")); // RFC-015
const SharePage = lazy(() => import("../features/share/SharePage")); // RFC-017

export default function AppRoutes() {
  return (
    <Suspense fallback={<p>Loading…</p>}>
      <Routes>
        <Route path="/" element={<LandingPage />} />
        <Route path="/upload" element={<UploadPage />} />
        <Route path="/review" element={<ReviewPage />} />
        <Route path="/map" element={<MapPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="/privacy" element={<PrivacyPage />} />
        <Route path="/s/:token" element={<SharePage />} />
      </Routes>
    </Suspense>
  );
}
