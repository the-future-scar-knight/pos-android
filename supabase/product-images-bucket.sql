-- Product images: Supabase Storage bucket + write policies.
--
-- Run this ONCE in the SQL editor of the shared POS project (ucgvvxlhdooevngtraje).
-- The Android app copies a picked image into on-device storage, then (once Cloud
-- sync "Upload this device's data" is enabled) uploads it to this bucket and stores
-- the resulting PUBLIC URL in products.image_url — which the web/storefront already
-- reads. The products.image_url + show_image columns already exist; nothing else
-- in the schema changes.
--
-- The bucket is PUBLIC (read), so product photos load without auth (matching how the
-- storefront serves them). WRITE is restricted to signed-in POS staff via
-- public.is_pos_staff() (already defined and used by the other POS RLS policies).

-- 1) The bucket (public read).
insert into storage.buckets (id, name, public)
values ('product-images', 'product-images', true)
on conflict (id) do nothing;

-- 2) Write policies on storage.objects, scoped to this bucket + POS staff.
--    (Public read needs no select policy — a public bucket serves /object/public/…)
create policy "pos staff insert product-images"
  on storage.objects for insert to authenticated
  with check (bucket_id = 'product-images' and public.is_pos_staff());

create policy "pos staff update product-images"
  on storage.objects for update to authenticated
  using (bucket_id = 'product-images' and public.is_pos_staff());

create policy "pos staff delete product-images"
  on storage.objects for delete to authenticated
  using (bucket_id = 'product-images' and public.is_pos_staff());
