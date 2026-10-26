import { createBrowserRouter, Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/stores/authStore';
import MainLayout from '@/layouts/MainLayout';

const PrivateRoute = () => {
  const token = useAuthStore((state) => state.token);
  const generation = useAuthStore((state) => state.generation);
  if (!token) {
    return <Navigate to="/login" replace />;
  }
  return <Outlet key={generation} />;
};

const LegacyEditor = () => {
  const location = useLocation();
  return <Navigate to={'/resumes/edit' + location.search} replace />;
};

const router = createBrowserRouter([
  {
    path: '/',
    element: <Navigate to="/interview" replace />,
  },
  {
    path: '/login',
    lazy: async () => ({ Component: (await import('@/pages/Login')).default }),
  },
  {
    path: '/register',
    lazy: async () => ({ Component: (await import('@/pages/Register')).default }),
  },
  {
    path: '/',
    element: <PrivateRoute />,
    children: [
      {
        element: <MainLayout />,
        children: [
          {
            path: '/resumes',
            lazy: async () => ({ Component: (await import('@/pages/Resumes/List')).default }),
          },
          {
            path: '/resumes/edit',
            lazy: async () => ({ Component: (await import('@/pages/Resumes/Editor')).default }),
          },
          {
            path: '/target-jobs',
            lazy: async () => ({ Component: (await import('@/pages/TargetJobs')).default }),
          },
          {
            path: '/profile',
            lazy: async () => ({ Component: (await import('@/pages/Profile')).default }),
          },
          {
            path: '/interview',
            lazy: async () => ({ Component: (await import('@/pages/Interview')).default }),
          },
          { path: '/app/analysis', element: <LegacyEditor /> },
          { path: '*', element: <Navigate to="/interview" replace /> },
        ],
      },
    ],
  },
]);

export default router;
