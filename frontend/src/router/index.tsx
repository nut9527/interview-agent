import { createBrowserRouter, Navigate, Outlet } from 'react-router-dom';
import { useAuthStore } from '@/stores/authStore';
import MainLayout from '@/layouts/MainLayout';
import PracticeLibraryLayout from '@/layouts/PracticeLibraryLayout';

const PrivateRoute = () => {
  const token = useAuthStore((state) => state.token);
  const generation = useAuthStore((state) => state.generation);
  if (!token) {
    return <Navigate to="/login" replace />;
  }
  return <Outlet key={generation} />;
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
          { path: '/learning/goals', lazy: async () => ({ Component: (await import('@/pages/Learning/Goals')).default }) },
          { path: '/learning/memory', lazy: async () => ({ Component: (await import('@/pages/Learning/MemoryReview')).default }) },
          { element: <PracticeLibraryLayout />, children: [
          { path: '/knowledge', lazy: async () => ({ Component: (await import('@/pages/Knowledge')).default }) },
          { path: '/knowledge/history', lazy: async () => ({ Component: (await import('@/pages/Knowledge/AnswerHistory')).default }) },
          { path: '/knowledge/practice', lazy: async () => ({ Component: (await import('@/pages/Knowledge/PracticeSet')).default }) },
          { path: '/knowledge/feedback', lazy: async () => ({ Component: (await import('@/pages/Knowledge/FeedbackLog')).default }) },
          {
            path: '/questions',
            lazy: async () => ({ Component: (await import('@/pages/QuestionBank')).default }),
          },
          {
            path: '/questions/:id',
            lazy: async () => ({ Component: (await import('@/pages/QuestionBank/Detail')).default }),
          },
          {
            path: '/questions/:id/practices',
            lazy: async () => ({ Component: (await import('@/pages/QuestionBank/PracticeHistory')).default }),
          },
          {
            path: '/questions/practice',
            lazy: async () => ({ Component: (await import('@/pages/QuestionBank/PracticeGroup')).default }),
          },
          ] },
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
          { path: '*', element: <Navigate to="/interview" replace /> },
        ],
      },
    ],
  },
]);

export default router;
