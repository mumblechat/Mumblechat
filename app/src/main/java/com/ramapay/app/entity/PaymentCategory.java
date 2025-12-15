package com.ramapay.app.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Predefined payment categories for PoS transactions
 */
public class PaymentCategory
{
    private final String id;
    private final String name;
    private final String icon;  // Emoji or icon reference

    public PaymentCategory(String id, String name, String icon)
    {
        this.id = id;
        this.name = name;
        this.icon = icon;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getIcon() { return icon; }
    
    public String getDisplayName() { return icon + " " + name; }

    /**
     * Get all predefined payment categories (25 categories)
     */
    public static List<PaymentCategory> getAllCategories()
    {
        List<PaymentCategory> categories = new ArrayList<>();
        
        // Daily Needs & Essentials
        categories.add(new PaymentCategory("grocery", "Grocery", "🛒"));
        categories.add(new PaymentCategory("food", "Food & Dining", "🍽️"));
        categories.add(new PaymentCategory("daily_needs", "Daily Needs", "🏪"));
        categories.add(new PaymentCategory("pharmacy", "Pharmacy/Medicine", "💊"));
        categories.add(new PaymentCategory("stationary", "Stationary", "📝"));
        
        // Transportation & Fuel
        categories.add(new PaymentCategory("fuel", "Fuel/Petrol", "⛽"));
        categories.add(new PaymentCategory("transport", "Transport/Travel", "🚗"));
        categories.add(new PaymentCategory("parking", "Parking", "🅿️"));
        
        // Bills & Utilities
        categories.add(new PaymentCategory("rent", "Rent", "🏠"));
        categories.add(new PaymentCategory("electricity", "Electricity Bill", "💡"));
        categories.add(new PaymentCategory("water", "Water Bill", "💧"));
        categories.add(new PaymentCategory("gas", "Gas Bill", "🔥"));
        categories.add(new PaymentCategory("internet", "Internet/WiFi", "📶"));
        categories.add(new PaymentCategory("mobile", "Mobile Recharge", "📱"));
        
        // Education
        categories.add(new PaymentCategory("school_fee", "School Fee", "🎒"));
        categories.add(new PaymentCategory("college_fee", "College/Tuition", "🎓"));
        categories.add(new PaymentCategory("coaching", "Coaching/Classes", "📚"));
        
        // EMI & Loans
        categories.add(new PaymentCategory("emi", "EMI Payment", "📅"));
        categories.add(new PaymentCategory("loan", "Loan Repayment", "🏦"));
        
        // Major Purchases
        categories.add(new PaymentCategory("purchase_asset", "Asset Purchase", "📦"));
        categories.add(new PaymentCategory("electronics", "Electronics", "📺"));
        categories.add(new PaymentCategory("home", "Home/Furniture", "🛋️"));
        categories.add(new PaymentCategory("car", "Car/Vehicle", "🚙"));
        
        // Services
        categories.add(new PaymentCategory("software", "Software/Subscription", "💻"));
        categories.add(new PaymentCategory("service", "Services", "🔧"));
        
        // Healthcare
        categories.add(new PaymentCategory("healthcare", "Healthcare", "🏥"));
        categories.add(new PaymentCategory("insurance", "Insurance", "🛡️"));
        
        // Shopping & Entertainment
        categories.add(new PaymentCategory("shopping", "Shopping", "🛍️"));
        categories.add(new PaymentCategory("entertainment", "Entertainment", "🎬"));
        
        // Others
        categories.add(new PaymentCategory("donation", "Donation/Charity", "❤️"));
        categories.add(new PaymentCategory("gift", "Gift", "🎁"));
        categories.add(new PaymentCategory("other", "Other", "📋"));
        
        return categories;
    }

    /**
     * Get category by ID
     */
    public static PaymentCategory getById(String id)
    {
        if (id == null) return null;
        
        for (PaymentCategory category : getAllCategories())
        {
            if (category.getId().equals(id))
            {
                return category;
            }
        }
        return null;
    }

    /**
     * Get category name with icon by ID
     */
    public static String getDisplayNameById(String id)
    {
        PaymentCategory category = getById(id);
        return category != null ? category.getDisplayName() : "Other";
    }
}
