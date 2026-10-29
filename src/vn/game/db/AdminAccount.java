package vn.game.db;

import vn.game.model.User;

/** Local operator command; deliberately not exposed as an HTTP endpoint. */
public final class AdminAccount {
    public static User create(UserRepository repo,String username,String password) throws Exception {
        username=username==null?null:username.trim();
        if(username==null||username.isBlank()||username.length()>64||password==null||password.length()<12||password.length()>128||password.isBlank())throw new IllegalArgumentException("Admin username: 1-64 characters; password: 12-128 characters.");
        if(repo.findByUsername(username)!=null)throw new IllegalArgumentException("Account already exists. Use --reset for an existing administrator.");
        User user=repo.register(username,null,password);user.setRole("admin");user.setPasswordHash(User.secureHashPassword(password,user.getSalt()));repo.update(user);return user;
    }
    public static void bootstrap(UserRepository repo) throws Exception {
        String username=vn.game.config.Env.get("ADMIN_BOOTSTRAP_USERNAME"),password=vn.game.config.Env.get("ADMIN_BOOTSTRAP_PASSWORD");
        if(username.isBlank()||password.isBlank())return;
        try(java.sql.Connection c=AdminStore.connect();java.sql.Statement st=c.createStatement();java.sql.ResultSet rs=st.executeQuery("SELECT COUNT(*) FROM users WHERE LOWER(role)='admin'")){
            if(rs.next()&&rs.getInt(1)>0)return;
        }
        create(repo,username,password);AdminStore.audit(username,"ADMIN_BOOTSTRAP","account",java.util.Map.of());
        System.out.println("Administrator initialized. Sign in at /admin.");
    }
    public static void main(String[] args) {
        DatabaseManager db=DatabaseManager.getInstance();
        db.init();
        try {
            UserRepository repo=new UserRepository(db);
            if(args.length>=1&&args[0].equals("--generate")){
                String base=args.length>1?args[1].trim():"admin_portal",name=base;
                for(int suffix=2;repo.findByUsername(name)!=null;suffix++)name=base+"_"+suffix;
                byte[] random=new byte[18];new java.security.SecureRandom().nextBytes(random);
                String password=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random);
                User created=create(repo,name,password);
                if(!repo.findByUsername(created.getUsername()).verifyPassword(password))throw new IllegalStateException("Cannot verify generated account");
                AdminStore.audit(created.getUsername(),"ADMIN_LOCAL_GENERATE","account",java.util.Map.of());
                System.out.println("ADMIN_USERNAME="+created.getUsername());
                System.out.println("ADMIN_PASSWORD="+password);
                return;
            }
            if(args.length>=1&&(args[0].equals("--create")||args[0].equals("--reset"))){
                java.io.Console console=System.console();if(console==null)throw new IllegalStateException("Open setup-admin.bat in an interactive terminal, or use ADMIN_BOOTSTRAP_USERNAME/PASSWORD environment variables for initial setup.");
                String username=args.length>1?args[1]:console.readLine("Admin username: ");
                char[] first=console.readPassword("Password (12-128 characters): "),second=console.readPassword("Confirm password: ");
                try{
                    if(first==null||second==null||!java.util.Arrays.equals(first,second))throw new IllegalArgumentException("Passwords do not match.");
                    String password=new String(first);
                    if(args[0].equals("--create"))create(repo,username,password);
                    else{User user=repo.findByUsername(username);if(user==null||!user.isAdmin())throw new IllegalArgumentException("Administrator does not exist.");if(password.length()<12||password.length()>128||password.isBlank())throw new IllegalArgumentException("Password must have 12-128 characters.");user.setSalt(User.generateSalt());user.setPasswordHash(User.secureHashPassword(password,user.getSalt()));repo.update(user);}
                    AdminStore.audit(username,"ADMIN_LOCAL_SETUP","account",java.util.Map.of("operation",args[0]));System.out.println("Administrator ready. Open http://localhost:3000/admin");
                }finally{if(first!=null)java.util.Arrays.fill(first,'\0');if(second!=null)java.util.Arrays.fill(second,'\0');}return;
            }
            if(args.length!=2||!("admin".equals(args[1])||"user".equals(args[1])))throw new IllegalArgumentException("Usage: AdminAccount --create [username] | --reset username | username admin|user");
            User user=repo.findByUsername(args[0]);
            if(user==null) throw new IllegalArgumentException("Account does not exist");
            user.setRole(args[1]); repo.update(user);
            if(repo.isAdmin(args[0])!= "admin".equals(args[1]))throw new IllegalStateException("Role update failed");
            System.out.println("Updated account role: " + user.getUsername() + " -> " + args[1]);
        } catch(Exception e){throw new IllegalStateException(e.getMessage(),e);} finally { db.close(); }
    }
}
