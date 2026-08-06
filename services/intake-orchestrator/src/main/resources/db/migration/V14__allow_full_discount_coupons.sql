alter table coupons drop constraint coupons_value_check;
alter table coupons add constraint coupons_value_check check (
    (discount_type = 'FIXED' and discount_value > 0)
    or (discount_type = 'PERCENTAGE' and discount_value > 0 and discount_value <= 100)
);

alter table subscriptions drop constraint subscriptions_amount_check;
alter table subscriptions add constraint subscriptions_amount_check check (
    original_amount_cop > 0 and current_amount_cop >= 0
);
