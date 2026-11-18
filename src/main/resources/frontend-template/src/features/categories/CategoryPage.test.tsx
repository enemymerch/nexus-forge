import { describe, it, expect } from 'vitest';
import { screen } from '@testing-library/react';
import { renderPage } from '../../test/render';
import { CategoryPage } from './CategoryPage';
describe('Category workspace', () => {
  it('has a page title', () => { renderPage(<CategoryPage/>); expect(screen.getByRole('heading', {name: 'Categories'})).toBeInTheDocument(); });
});
